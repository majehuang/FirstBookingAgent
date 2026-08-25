package io.agentharness.tui.render;

import org.jline.utils.WCWidth;

/**
 * 文本在终端里占几列。
 *
 * <p><b>不能用 {@code String.length()} 代替。</b>中文一个字符占两列，
 * 而状态行里几乎每个标签都是中文 —— 按字符数排版的话，
 * 一行"看起来"没超宽，实际却撑破终端而折行，把状态行顶成两行。
 *
 * <p>宽度判定委托给 JLine 的 {@link WCWidth}（标准 wcwidth 实现），
 * 不自己维护码点区间表：那张表要跟着 Unicode 版本走，手写的迟早过期。
 */
public final class DisplayWidth {

    private DisplayWidth() {
    }

    /** 显示列数。控制字符与组合字符按 0 计。 */
    public static int of(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int columns = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            columns += Math.max(0, WCWidth.wcwidth(codePoint));
            i += Character.charCount(codePoint);
        }
        return columns;
    }

    /**
     * 截到不超过 {@code columns} 列，超了就带省略号。
     *
     * <p>按码点推进而不是按字符：中文在 Java 里是一个 char，
     * 但表情之类的补充平面字符是两个，从中间切开会得到一个乱码方块。
     */
    public static String truncate(String text, int columns) {
        if (columns <= 0) {
            return "";
        }
        if (of(text) <= columns) {
            return text;
        }
        // 留一列给省略号
        int budget = columns - 1;
        StringBuilder kept = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int w = Math.max(0, WCWidth.wcwidth(codePoint));
            if (used + w > budget) {
                break;
            }
            kept.appendCodePoint(codePoint);
            used += w;
            i += Character.charCount(codePoint);
        }
        return kept + "…";
    }
}
