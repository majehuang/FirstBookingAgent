package io.agentharness.tui.terminal;

import io.agentharness.tui.render.RenderedLine;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.InfoCmp;
import org.jline.utils.Status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 交互式终端实现。
 *
 * <p>布局是「滚动区在上、输入行在下、状态区固定在底部」：
 * <ul>
 *   <li>完整行走 {@code printAbove}，从输入行上方推入滚动历史 ——
 *       这是 JLine 里唯一能在 readLine 阻塞期间安全打印的入口</li>
 *   <li>未完成的流式尾巴与状态行走 {@code Status}，就地刷新，不进滚动历史</li>
 * </ul>
 *
 * <p>{@code Status} 在部分终端上不可用（返回 null），此时降级为只打印完整行，
 * 会话依然可用，只是少了实时尾巴与状态条。
 */
public final class JLineTerminalUi implements TerminalUi {

    private static final String ELLIPSIS = "…";

    private final Terminal terminal;
    private final LineReader reader;
    private final Status status;

    private volatile String liveTail = "";
    private volatile String statusText = "";

    private JLineTerminalUi(Terminal terminal, LineReader reader, Status status) {
        this.terminal = terminal;
        this.reader = reader;
        this.status = status;
    }

    public static JLineTerminalUi open(Path historyFile) {
        try {
            // 显式指定 UTF-8：宿主机 locale 不是 UTF-8 时，中文会变成问号，
            // 而这个故障只在别人的机器上出现，本地永远复现不了
            Terminal terminal = TerminalBuilder.builder()
                    .name("agent")
                    .system(true)
                    .encoding(StandardCharsets.UTF_8)
                    .build();

            LineReaderBuilder builder = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                    .option(LineReader.Option.AUTO_FRESH_LINE, true);
            if (historyFile != null) {
                builder.variable(LineReader.HISTORY_FILE, historyFile);
            }

            LineReader reader = builder.build();
            return new JLineTerminalUi(terminal, reader, attachStatus(terminal));
        } catch (IOException e) {
            throw new UncheckedIOException("无法打开交互式终端", e);
        }
    }

    /**
     * 状态区只在终端上报了有效尺寸时启用。
     *
     * <p>某些伪终端（{@code script}、部分 CI 容器）会报 0×0，此时 JLine 的 Status
     * 不但画不出来，还会在 {@code terminal.close()} 时空指针 —— 它是注册在终端上的，
     * 一旦创建就躲不掉。所以判断放在创建之前，而不是使用之前。
     */
    private static Status attachStatus(Terminal terminal) {
        Size size = terminal.getSize();
        if (size.getRows() <= 0 || size.getColumns() <= 0) {
            return null;
        }
        return Status.getStatus(terminal, true);
    }

    /** 终端是否可交互。dumb 终端（管道、CI）应当退回 PlainTerminalUi。 */
    public static boolean isInteractive() {
        try (Terminal probe = TerminalBuilder.builder().system(true).dumb(true).build()) {
            return !Terminal.TYPE_DUMB.equals(probe.getType())
                    && !Terminal.TYPE_DUMB_COLOR.equals(probe.getType());
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public ReadResult readLine(String prompt) {
        try {
            return new ReadResult.Line(reader.readLine(prompt));
        } catch (UserInterruptException e) {
            return new ReadResult.Interrupted();
        } catch (EndOfFileException e) {
            return new ReadResult.EndOfInput();
        }
    }

    @Override
    public void printLines(List<RenderedLine> lines) {
        for (RenderedLine line : lines) {
            reader.printAbove(Theme.style(line));
        }
    }

    @Override
    public void setLiveTail(String tail) {
        this.liveTail = tail == null ? "" : tail;
        refreshStatusArea();
    }

    @Override
    public void setStatus(String text) {
        this.statusText = text == null ? "" : text;
        refreshStatusArea();
    }

    @Override
    public int width() {
        int width = terminal.getWidth();
        return width > 0 ? width : 100;
    }

    @Override
    public boolean interactive() {
        return true;
    }

    @Override
    public void clearScreen() {
        terminal.puts(InfoCmp.Capability.clear_screen);
        terminal.flush();
    }

    @Override
    public void close() {
        try {
            if (status != null) {
                status.update(List.of());
            }
            terminal.flush();
            terminal.close();
        } catch (IOException e) {
            throw new UncheckedIOException("关闭终端失败", e);
        }
    }

    private void refreshStatusArea() {
        if (status == null) {
            return;
        }
        List<AttributedString> lines = new ArrayList<>(2);
        if (!liveTail.isEmpty()) {
            lines.add(Theme.liveTail(tailWindow(liveTail, width())));
        }
        if (!statusText.isEmpty()) {
            lines.add(Theme.status(statusText));
        }
        status.update(lines);
    }

    /**
     * 尾巴超宽时保留**末尾**而不是开头 —— 用户此刻关心的是刚吐出来的字，
     * 不是这一行开头写了什么。
     */
    static String tailWindow(String tail, int width) {
        if (width <= 1 || tail.length() <= width) {
            return tail;
        }
        return ELLIPSIS + tail.substring(tail.length() - (width - 1));
    }
}
