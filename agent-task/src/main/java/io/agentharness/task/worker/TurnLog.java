package io.agentharness.task.worker;

import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 每轮一行的常规日志。
 *
 * <p>与 {@link io.agentharness.trace.TraceSink} 的分工：追踪是逐环节的细节，
 * 开着很吵、默认关；这里是<b>一轮一行</b>的常规输出，worker 进程默认就开 ——
 * 一个只跑推理、没有界面的进程，不打这一行的话终端上什么都不会发生，
 * 看起来和挂死了没有区别。
 *
 * <p>刻意<b>不走 slf4j</b>：{@code agent-cli} 绑的是 {@code slf4j-nop}，
 * 所有 slf4j 输出都会被丢掉。要让这一行真的出现在控制台，就得自己写 stream。
 */
public interface TurnLog {

    /** 一轮结束。成功与失败都会调到这里。 */
    void turnFinished(TurnSummary summary);

    /** 什么都不打。内嵌在会话进程里的 worker 用它 —— 日志混进滚动区会盖住回复本身。 */
    static TurnLog disabled() {
        return summary -> {
        };
    }

    /** 打到 stdout。 */
    static TurnLog toStdout() {
        return toStream(System.out);
    }

    static TurnLog toStream(PrintStream out) {
        return summary -> out.println(summary.format());
    }

    /**
     * 一轮的结果。
     *
     * @param sessionId  会话
     * @param replyId    这一轮的 replyId，与状态条上的 {@code ⌁ r-…} 对得上
     * @param engineName 引擎名 —— "到底接没接上模型"是看日志时最常问的一句
     * @param events     引擎吐出的原始事件数
     * @param messages   落库并进 outbox 的消息数
     * @param elapsed    耗时
     * @param failure    失败原因；成功为 {@code null}
     */
    record TurnSummary(
            Instant at,
            String sessionId,
            String replyId,
            String engineName,
            long events,
            long messages,
            Duration elapsed,
            String failure) {

        private static final DateTimeFormatter CLOCK =
                DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

        public static TurnSummary done(Instant at, String sessionId, String replyId,
                                       String engineName, long events, long messages,
                                       Duration elapsed) {
            return new TurnSummary(at, sessionId, replyId, engineName, events, messages,
                    elapsed, null);
        }

        public static TurnSummary failed(Instant at, String sessionId, String replyId,
                                         String engineName, long events, long messages,
                                         Duration elapsed, String failure) {
            return new TurnSummary(at, sessionId, replyId, engineName, events, messages, elapsed,
                    failure == null ? "未知原因" : failure);
        }

        public boolean succeeded() {
            return failure == null;
        }

        /**
         * 排成一行。
         *
         * <p>列宽固定：日志最常见的读法是竖着扫某一列（哪一轮变慢了、哪个会话在失败），
         * 列对不齐就只能逐行读。
         */
        public String format() {
            String head = String.format("%s  %-12s %-14s", CLOCK.format(at), sessionId, replyId);
            if (!succeeded()) {
                return head + " ✗ 失败   " + failure + "   " + seconds() + "  " + engineName;
            }
            return head + String.format(" ✓ 完成   事件 %-4d 消息 %-4d %s  %s",
                    events, messages, seconds(), engineName);
        }

        private String seconds() {
            return String.format("%.1fs", elapsed.toMillis() / 1000.0);
        }
    }
}
