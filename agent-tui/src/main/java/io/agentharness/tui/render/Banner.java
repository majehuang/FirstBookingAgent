package io.agentharness.tui.render;

import io.agentharness.protocol.SessionRef;
import io.agentharness.tui.input.SlashCommand;
import io.agentharness.tui.state.UiState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 欢迎语、/help、/status 三块静态文本的渲染。纯函数。 */
public final class Banner {

    private Banner() {
    }

    public static List<RenderedLine> welcome(SessionRef session, String backendName) {
        return List.of(
                RenderedLine.of(LineKind.SYSTEM, "Agent 终端  ·  " + backendName),
                RenderedLine.hint("会话 " + session + "    输入 /help 查看命令，^C 停止当前回复，^D 退出"),
                RenderedLine.hint(""));
    }

    public static List<RenderedLine> help() {
        List<RenderedLine> lines = new ArrayList<>();
        lines.add(RenderedLine.of(LineKind.SYSTEM, "命令"));
        for (SlashCommand command : SlashCommand.values()) {
            String invocation = "/" + command.commandName()
                    + (command.usage().isEmpty() ? "" : " " + command.usage());
            String aliases = command.aliases().isEmpty() ? ""
                    : "  （别名 " + String.join(" ", command.aliases().stream().map(a -> "/" + a).toList()) + "）";
            lines.add(RenderedLine.hint(String.format("  %-22s %s%s",
                    invocation, command.description(), aliases)));
        }
        return List.copyOf(lines);
    }

    public static List<RenderedLine> status(UiState state, Instant now) {
        List<RenderedLine> lines = new ArrayList<>();
        lines.add(RenderedLine.of(LineKind.SYSTEM, "会话状态"));
        lines.add(RenderedLine.hint("  用户          " + state.session().userId()));
        lines.add(RenderedLine.hint("  会话          " + state.session().sessionId()));
        lines.add(RenderedLine.hint("  后端          " + state.backendName()));
        lines.add(RenderedLine.hint("  连接          " + state.connection().label()));
        lines.add(RenderedLine.hint("  阶段          " + state.phase().label()));
        lines.add(RenderedLine.hint("  本地最大 seq  " + state.lastMsgSeq()));
        lines.add(RenderedLine.hint("  空窗次数      " + state.gapCount()));
        if (state.hasPendingInput()) {
            lines.add(RenderedLine.hint("  投递中        " + state.pendingInput()));
        }
        lines.add(RenderedLine.hint("  当前 replyId  "
                + (state.control().activeReplyId() == null ? "—" : state.control().activeReplyId())));
        lines.add(RenderedLine.hint("  ctrl 水位     "
                + (state.control().ctrlId() == null ? "—" : state.control().ctrlId())));
        if (state.control().turnActive()) {
            lines.add(RenderedLine.hint("  已耗时        " + StatusLine.formatElapsed(state.turnElapsed(now))));
        }
        return List.copyOf(lines);
    }
}
