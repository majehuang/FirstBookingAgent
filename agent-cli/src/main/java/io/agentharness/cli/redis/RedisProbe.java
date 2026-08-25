package io.agentharness.cli.redis;

import io.agentharness.cli.check.CheckResult;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.reactive.RedisReactiveCommands;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis 环境自检 —— 开发计划 P0-1 的自动化版本。
 *
 * <p>它要回答的是那三个「答案不同就要改整套设计」的问题：
 * <ol>
 *   <li>引擎版本是不是 7.0（阿里云没有 6.2 这一档，而 XAUTOCLAIM 与 MINID 都是 6.2 引入的）</li>
 *   <li>EVAL / EVALSHA 是否开放</li>
 *   <li><b>脚本内 {@code XADD ... *} 是否被允许</b> —— 文档没写明，只能实测。
 *       ctrl 状态脚本靠它把水位与 XADD 原子化（INV-11），被禁就得换设计</li>
 * </ol>
 *
 * <p>每项独立执行、独立报告：一项失败不影响其余项，
 * 这样一次运行就能拿到完整结论，不用反复试。
 */
public final class RedisProbe {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String PROBE_STREAM = "{agent-doctor}:probe:stream";
    private static final String PROBE_GROUP = "doctor";
    private static final int MIN_MAJOR = 7;

    private final String uri;

    public RedisProbe(String uri) {
        this.uri = uri;
    }

    public List<CheckResult> run() {
        List<CheckResult> results = new ArrayList<>();
        RedisClient client = RedisClient.create(uri);
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            RedisReactiveCommands<String, String> commands = connection.reactive();

            results.add(ping(commands));
            results.add(version(commands));
            results.add(architecture(commands));
            results.add(lua(commands));
            results.add(evalsha(commands));
            results.add(nonDeterministicXadd(commands));
            results.add(xautoclaim(commands));
            results.add(minId(commands));
            cleanup(commands);
        } catch (Exception e) {
            results.add(CheckResult.fail("连接", uri + " —— " + rootMessage(e)));
        } finally {
            client.shutdown();
        }
        return List.copyOf(results);
    }

    private CheckResult ping(RedisReactiveCommands<String, String> commands) {
        try {
            String pong = commands.ping().block(TIMEOUT);
            return CheckResult.ok("连接", uri + " → " + pong);
        } catch (Exception e) {
            return CheckResult.fail("连接", rootMessage(e));
        }
    }

    private CheckResult version(RedisReactiveCommands<String, String> commands) {
        try {
            String info = commands.info("server").block(TIMEOUT);
            String version = extract(info, "redis_version:");
            if (version.isEmpty()) {
                return CheckResult.warn("引擎版本", "未能从 INFO 解析出版本");
            }
            int major = majorOf(version);
            if (major >= MIN_MAJOR) {
                return CheckResult.ok("引擎版本", version);
            }
            return CheckResult.fail("引擎版本",
                    version + " —— 需要 7.0（6.0 没有 XAUTOCLAIM/MINID，回收机制无法实现）");
        } catch (Exception e) {
            return CheckResult.fail("引擎版本", rootMessage(e));
        }
    }

    private CheckResult architecture(RedisReactiveCommands<String, String> commands) {
        try {
            String info = commands.info("cluster").block(TIMEOUT);
            boolean clustered = "1".equals(extract(info, "cluster_enabled:"));
            return clustered
                    ? CheckResult.warn("架构", "集群版 —— Lua 受同槽约束，务必用直连模式而非代理模式")
                    : CheckResult.ok("架构", "标准版（主从）—— Lua 无槽约束，hash tag 零成本保留");
        } catch (Exception e) {
            return CheckResult.warn("架构", "无法判定：" + rootMessage(e));
        }
    }

    private CheckResult lua(RedisReactiveCommands<String, String> commands) {
        try {
            String reply = commands.<String>eval("return 'pong'", ScriptOutputType.VALUE, new String[0]).blockLast(TIMEOUT);
            return "pong".equals(reply)
                    ? CheckResult.ok("EVAL", "可用")
                    : CheckResult.warn("EVAL", "返回值异常：" + reply);
        } catch (Exception e) {
            return CheckResult.fail("EVAL", rootMessage(e) + " —— 摘牌与 ctrl 脚本无法实现，需走兜底扫描方案");
        }
    }

    private CheckResult evalsha(RedisReactiveCommands<String, String> commands) {
        try {
            String sha = commands.scriptLoad("return 'loaded'").block(TIMEOUT);
            String reply = commands.<String>evalsha(sha, ScriptOutputType.VALUE, new String[0]).blockLast(TIMEOUT);
            return "loaded".equals(reply)
                    ? CheckResult.ok("SCRIPT LOAD / EVALSHA", sha.substring(0, 12) + "…")
                    : CheckResult.warn("SCRIPT LOAD / EVALSHA", "返回值异常：" + reply);
        } catch (Exception e) {
            return CheckResult.fail("SCRIPT LOAD / EVALSHA", rootMessage(e));
        }
    }

    private CheckResult nonDeterministicXadd(RedisReactiveCommands<String, String> commands) {
        String script = "return redis.call('XADD', KEYS[1], '*', 'f', 'v')";
        try {
            String id = commands.<String>eval(script, ScriptOutputType.VALUE, new String[]{PROBE_STREAM})
                    .blockLast(TIMEOUT);
            return CheckResult.ok("脚本内 XADD *", "生成 " + id + " —— ctrl 水位可原子产生（INV-11）");
        } catch (Exception e) {
            return CheckResult.fail("脚本内 XADD *",
                    rootMessage(e) + " —— 水位需改由客户端生成，INV-11 要重新设计");
        }
    }

    private CheckResult xautoclaim(RedisReactiveCommands<String, String> commands) {
        String script = """
                redis.pcall('XGROUP', 'CREATE', KEYS[1], ARGV[1], '0', 'MKSTREAM')
                local r = redis.pcall('XAUTOCLAIM', KEYS[1], ARGV[1], 'doctor', 0, '0-0')
                if type(r) == 'table' and r.err then return 'ERR:' .. r.err end
                return 'OK'
                """;
        return luaCapability(commands, "XAUTOCLAIM", script,
                "PEL 回收可用（INV-2b）", "回收机制无法实现，session 会静默永久卡死");
    }

    private CheckResult minId(RedisReactiveCommands<String, String> commands) {
        String script = """
                local r = redis.pcall('XTRIM', KEYS[1], 'MINID', '0')
                if type(r) == 'table' and r.err then return 'ERR:' .. r.err end
                return 'OK'
                """;
        return luaCapability(commands, "XTRIM MINID", script,
                "可按时间裁剪 outbox", "只能按条数裁剪，保留窗口不精确");
    }

    private CheckResult luaCapability(RedisReactiveCommands<String, String> commands,
                                      String name, String script, String okDetail, String failDetail) {
        try {
            String reply = commands.<String>eval(script, ScriptOutputType.VALUE,
                    new String[]{PROBE_STREAM}, PROBE_GROUP).blockLast(TIMEOUT);
            if ("OK".equals(reply)) {
                return CheckResult.ok(name, okDetail);
            }
            return CheckResult.fail(name, reply + " —— " + failDetail);
        } catch (Exception e) {
            return CheckResult.skip(name, "未执行：" + rootMessage(e));
        }
    }

    private void cleanup(RedisReactiveCommands<String, String> commands) {
        try {
            commands.del(PROBE_STREAM).block(TIMEOUT);
        } catch (Exception ignored) {
            // 自检残留一个 key 不值得让命令失败
        }
    }

    static String extract(String info, String prefix) {
        if (info == null) {
            return "";
        }
        for (String line : info.split("\r?\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).strip();
            }
        }
        return "";
    }

    static int majorOf(String version) {
        int dot = version.indexOf('.');
        String head = dot < 0 ? version : version.substring(0, dot);
        try {
            return Integer.parseInt(head.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }
}
