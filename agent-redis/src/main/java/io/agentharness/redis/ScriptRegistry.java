package io.agentharness.redis;

import io.lettuce.core.ScriptOutputType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lua 脚本的加载与执行：<b>启动 {@code SCRIPT LOAD}，运行期 {@code EVALSHA}</b>。
 *
 * <p>为什么不每次都发完整脚本（{@code EVAL}）：摘牌在每个 turn 的收尾都要跑一次，
 * 脚本正文几 KB，而 SHA 是 40 字节。省的不是 CPU 是带宽，
 * 但更重要的是 {@code EVAL} 会让脚本正文出现在慢查询日志和 {@code MONITOR} 输出里，
 * 排查时满屏都是 Lua 源码。
 *
 * <h2>NOSCRIPT 的处理</h2>
 * Redis 重启、主从切换、或者有人手工 {@code SCRIPT FLUSH} 之后，缓存的 SHA 就失效了，
 * {@code EVALSHA} 返回 {@code NOSCRIPT}。这时<b>只能重新加载再重试，绝不能退化成
 * 跳过校验的普通命令</b> —— 摘牌脚本的全部价值就在那两步校验上（LUA-004）。
 *
 * <p>重试次数定死为 1：一次 reload 之后还是 NOSCRIPT，说明问题不在缓存
 * （比如连到了另一个节点、或者 Redis 正在反复重启），继续重试只是把故障拖长。
 */
public final class ScriptRegistry {

    private static final Logger log = LoggerFactory.getLogger(ScriptRegistry.class);

    /** NOSCRIPT 之后重新加载并重试的次数。见类注释：定死为 1。 */
    private static final int RELOAD_ATTEMPTS = 1;

    private static final String NOSCRIPT = "NOSCRIPT";
    private static final String RESOURCE_PREFIX = "/redis/";

    private final RedisRuntime runtime;

    /** 脚本名 → 正文。启动时一次性读完，运行期不再碰文件系统。 */
    private final Map<String, String> sources = new ConcurrentHashMap<>();

    /** 脚本名 → SHA。NOSCRIPT 之后会被替换。 */
    private final Map<String, String> digests = new ConcurrentHashMap<>();

    public ScriptRegistry(RedisRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * 启动时加载。
     *
     * <p>放在启动而不是首次使用：首次使用时加载的话，加载失败会表现为
     * "第一个 turn 收尾失败"，而那时候已经有用户在等回复了。启动时失败则进程起不来，
     * 部署系统会拦住它。
     */
    public Mono<Void> load(String name) {
        String source = readSource(name);
        sources.put(name, source);
        return runtime.commands().scriptLoad(source)
                .doOnNext(sha -> {
                    digests.put(name, sha);
                    log.debug("已加载 Lua 脚本 {} sha={}", name, sha);
                })
                .onErrorMap(e -> new RedisException("加载 Lua 脚本 " + name + " 失败", e))
                .then();
    }

    /** 执行。首选 {@code EVALSHA}，只有 NOSCRIPT 才回退到重新加载。 */
    public <T> Mono<T> eval(String name, ScriptOutputType outputType,
                            String[] keys, String... args) {
        return this.<T>evalOnce(name, outputType, keys, args)
                .onErrorResume(error -> isNoScript(error)
                        ? this.<T>reloadAndRetry(name, outputType, keys, args, RELOAD_ATTEMPTS)
                        : Mono.error(error));
    }

    private <T> Mono<T> reloadAndRetry(String name, ScriptOutputType outputType,
                                       String[] keys, String[] args, int attemptsLeft) {
        if (attemptsLeft <= 0) {
            return Mono.error(new RedisException(
                    "Lua 脚本 " + name + " 重新加载后仍报 NOSCRIPT，放弃重试"));
        }
        log.warn("Lua 脚本 {} 的 SHA 已失效（Redis 重启或 SCRIPT FLUSH），重新加载", name);
        return load(name)
                .then(Mono.defer(() -> this.<T>evalOnce(name, outputType, keys, args)))
                .onErrorResume(error -> isNoScript(error)
                        ? this.<T>reloadAndRetry(name, outputType, keys, args, attemptsLeft - 1)
                        : Mono.error(error));
    }

    private <T> Mono<T> evalOnce(String name, ScriptOutputType outputType,
                                 String[] keys, String[] args) {
        String sha = digests.get(name);
        if (sha == null) {
            return Mono.error(new RedisException(
                    "Lua 脚本 " + name + " 未加载：启动时应当调用 load(" + name + ")"));
        }
        return runtime.commands().<T>evalsha(sha, outputType, keys, args).next();
    }

    /** 诊断用：当前缓存的 SHA。测试用它确认走的是 EVALSHA 而不是每次重发正文。 */
    public String digestOf(String name) {
        return digests.get(name);
    }

    static boolean isNoScript(Throwable error) {
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            if (cursor.getMessage() != null && cursor.getMessage().contains(NOSCRIPT)) {
                return true;
            }
            if (cursor.getCause() == cursor) {
                break;
            }
        }
        return false;
    }

    private static String readSource(String name) {
        String path = RESOURCE_PREFIX + name;
        try (InputStream in = ScriptRegistry.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new RedisException("找不到 Lua 脚本资源 " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 Lua 脚本 " + path + " 失败", e);
        }
    }
}
