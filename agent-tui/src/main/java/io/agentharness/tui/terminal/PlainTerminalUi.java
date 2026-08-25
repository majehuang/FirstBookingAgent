package io.agentharness.tui.terminal;

import io.agentharness.tui.render.RenderedLine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 非交互终端的实现：逐行读、逐行写，没有状态行也没有实时尾巴。
 *
 * <p>存在的意义是让验收可以脚本化：
 * <pre>printf '你好\n/status\n/quit\n' | agent</pre>
 * 输出是确定的纯文本，可以直接 diff。交互体验交给 JLine 那个实现。
 */
public final class PlainTerminalUi implements TerminalUi {

    private static final int DEFAULT_WIDTH = 100;

    private final BufferedReader input;
    private final PrintStream output;
    private final boolean echoPrompt;

    public PlainTerminalUi(InputStream in, PrintStream out, boolean echoPrompt) {
        this.input = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.output = out;
        this.echoPrompt = echoPrompt;
    }

    public static PlainTerminalUi standard() {
        return new PlainTerminalUi(System.in,
                new PrintStream(System.out, true, StandardCharsets.UTF_8), false);
    }

    @Override
    public ReadResult readLine(String prompt) {
        try {
            if (echoPrompt) {
                output.print(prompt);
                output.flush();
            }
            String line = input.readLine();
            return line == null ? new ReadResult.EndOfInput() : new ReadResult.Line(line);
        } catch (IOException e) {
            output.println("读取输入失败：" + e.getMessage());
            return new ReadResult.EndOfInput();
        }
    }

    @Override
    public void printLines(List<RenderedLine> lines) {
        for (RenderedLine line : lines) {
            output.println(line.text());
        }
        output.flush();
    }

    @Override
    public void setLiveTail(String tail) {
        // 非交互模式不做实时刷新：未完成的尾巴会在块结束时作为完整行落地
    }

    @Override
    public void setStatus(String status) {
        // 非交互模式不展示状态行，避免污染管道输出
    }

    @Override
    public int width() {
        String columns = System.getenv("COLUMNS");
        if (columns == null || columns.isBlank()) {
            return DEFAULT_WIDTH;
        }
        try {
            int parsed = Integer.parseInt(columns.strip());
            return parsed > 0 ? parsed : DEFAULT_WIDTH;
        } catch (NumberFormatException e) {
            return DEFAULT_WIDTH;
        }
    }

    @Override
    public boolean interactive() {
        return false;
    }

    @Override
    public void clearScreen() {
        // 管道里清屏没有意义
    }

    @Override
    public void close() {
        output.flush();
    }
}
