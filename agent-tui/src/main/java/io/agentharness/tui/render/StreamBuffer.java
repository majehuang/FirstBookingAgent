package io.agentharness.tui.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 流式文本的行缓冲。不可变：{@link #append} 返回新的缓冲与本次可以落地的完整行。
 *
 * <p>为什么要区分「完整行」与「尾巴」：终端一旦打印一行就收不回来了，
 * 而模型是逐 token 吐字的。所以只有遇到换行才真正打印，未完成的尾巴留在状态区实时刷新，
 * 这样既能看到逐字生成，又不会把半行文本永久烙在滚动历史里。
 */
public record StreamBuffer(String tail) {

    public StreamBuffer {
        Objects.requireNonNull(tail, "tail");
    }

    public static StreamBuffer empty() {
        return new StreamBuffer("");
    }

    /** 一次追加的结果：可以落地的完整行 + 新的缓冲状态。 */
    public record Append(StreamBuffer buffer, List<String> completedLines) {

        public Append {
            Objects.requireNonNull(buffer, "buffer");
            completedLines = List.copyOf(completedLines);
        }
    }

    public Append append(String delta) {
        if (delta == null || delta.isEmpty()) {
            return new Append(this, List.of());
        }

        String combined = tail + delta;
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < combined.length(); i++) {
            if (combined.charAt(i) == '\n') {
                lines.add(stripCarriageReturn(combined.substring(start, i)));
                start = i + 1;
            }
        }
        return new Append(new StreamBuffer(combined.substring(start)), lines);
    }

    /** 结束一个文本块：把尾巴作为最后一行落地。尾巴为空时不产生空行。 */
    public Append flush() {
        if (tail.isEmpty()) {
            return new Append(empty(), List.of());
        }
        return new Append(empty(), List.of(tail));
    }

    public boolean hasPending() {
        return !tail.isEmpty();
    }

    private static String stripCarriageReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
