package io.agentharness.keys;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 架构守护：**除了 {@link KeyNamespace}，任何地方都不许出现 Redis 键名字面量。**
 *
 * <p>这条不是洁癖。模块之间没有 RPC 也没有共享内存，接口就是那张键表 ——
 * 一旦有人在别处拼出第二份键名，契约就有了两个版本。
 * 而两个版本不一致时的表现是"消息投进去了但没人消费"：
 * Redis 没有报错，日志里没有异常，只有用户觉得机器人不理他。
 *
 * <p>用源码扫描而不是 ArchUnit：字符串常量在字节码里会被内联和拼接，
 * 按字节码查反而不如直接看源码可靠。
 */
class KeyLiteralGuardTest {

    /** 只有这个模块可以定义键名。 */
    private static final String OWNER_MODULE = "agent-keys";

    /**
     * 被禁的形态。key 是模式，value 是说明 —— 断言失败时要让人立刻知道该怎么改。
     */
    private static final Map<Pattern, String> FORBIDDEN = Map.of(
            Pattern.compile("\":sess:|:sess:\""),
            "session key 的分段，应当调用 KeyNamespace.inbox/outbox/cursor/lease/state/ctrlStream",
            Pattern.compile("\"\\{s\\d|\\{s\\d{1,3}}"),
            "hash tag 字面量，应当调用 KeyNamespace.hashTag",
            Pattern.compile("\"ready\""),
            "全局唤醒队列，应当引用 KeyNamespace.READY");

    @Test
    @DisplayName("生产代码里不允许出现 Redis 键名字面量")
    void 没有模块绕过KeyNamespace() {
        List<String> violations = new ArrayList<>();

        for (Path source : productionSources()) {
            String content = read(source);
            FORBIDDEN.forEach((pattern, reason) -> {
                if (pattern.matcher(content).find()) {
                    violations.add(relative(source) + " —— " + reason);
                }
            });
        }

        assertThat(violations)
                .as("发现绕过 KeyNamespace 的键名字面量")
                .isEmpty();
    }

    @Test
    void 守护测试本身能扫到文件_否则它会永远是绿的() {
        // 一条空的扫描列表会让上面那个断言永远通过，那比没有这个测试更糟
        assertThat(productionSources()).hasSizeGreaterThan(20);
    }

    @Test
    void 能识别出违规写法() {
        // 反向验证：把该抓的写法喂给模式，确认它真的会被抓到
        assertThat(matchesAny("String key = \"{s007}:sess:\" + sid + \":inbox\";")).isTrue();
        assertThat(matchesAny("redis.xadd(\"ready\", token);")).isTrue();
        assertThat(matchesAny("String key = KeyNamespace.inbox(sessionId);")).isFalse();
    }

    private static boolean matchesAny(String line) {
        return FORBIDDEN.keySet().stream().anyMatch(p -> p.matcher(line).find());
    }

    /** 所有模块的生产源码，排除 {@link #OWNER_MODULE} 自身与 target 目录。 */
    private static List<Path> productionSources() {
        Path root = repositoryRoot();
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .filter(p -> !relative(p).startsWith(OWNER_MODULE + "/"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("扫描源码失败", e);
        }
    }

    /** 从当前工作目录向上找到带 {@code <modules>} 的父 pom。 */
    private static Path repositoryRoot() {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            Path pom = cursor.resolve("pom.xml");
            if (Files.exists(pom) && read(pom).contains("<modules>")) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("找不到仓库根目录（带 <modules> 的父 pom）");
    }

    private static String relative(Path path) {
        return repositoryRoot().relativize(path).toString();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + path + " 失败", e);
        }
    }
}
