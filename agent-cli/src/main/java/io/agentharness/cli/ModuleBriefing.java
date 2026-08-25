package io.agentharness.cli;

import io.agentharness.keys.KeyNamespace;

import java.util.List;

/**
 * 尚未实现的模块的自述。
 *
 * <p>与其让占位命令打印一句"未实现"，不如让它把**契约**打印出来：
 * 该模块读写哪些 key、由哪个阶段交付、必须守住哪几条不变量。
 * 模块之间没有 RPC 也没有服务发现，接口就是那张 key 表，
 * 所以这份自述本身就是接入文档。
 *
 * <p>键名一律从 {@link KeyNamespace} 现算而不是写死 ——
 * 写死的文档会在某次改名之后悄悄变成谎话，现算的不会。
 */
record ModuleBriefing(
        String module,
        String phase,
        List<String> responsibilities,
        List<String> reads,
        List<String> writes,
        List<String> invariants) {

    private static final int EXIT_NOT_IMPLEMENTED = 3;

    /** 打印契约时用的示例会话，让读者看到 key 的真实形态而不是占位符。 */
    private static final String SAMPLE_SESSION = "s-local";

    static String inbox() {
        return KeyNamespace.inbox(SAMPLE_SESSION);
    }

    static String outbox() {
        return KeyNamespace.outbox(SAMPLE_SESSION);
    }

    static String cursor() {
        return KeyNamespace.cursor(SAMPLE_SESSION);
    }

    static String lease() {
        return KeyNamespace.lease(SAMPLE_SESSION);
    }

    static String state() {
        return KeyNamespace.state(SAMPLE_SESSION);
    }

    static String ctrlStream() {
        return KeyNamespace.ctrlStream(SAMPLE_SESSION);
    }

    static String ready() {
        return KeyNamespace.READY;
    }

    int print() {
        System.out.println();
        System.out.println("  " + module + "    交付阶段 " + phase);
        System.out.println();
        section("职责", responsibilities);
        section("读取", reads);
        section("写入", writes);
        section("不变量", invariants);
        System.out.println("  当前可运行：agent（会话模式）、agent worker、agent doctor、agent migrate");
        System.out.println();
        return EXIT_NOT_IMPLEMENTED;
    }

    private static void section(String title, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        System.out.println("  " + title);
        items.forEach(item -> System.out.println("    · " + item));
        System.out.println();
    }
}
