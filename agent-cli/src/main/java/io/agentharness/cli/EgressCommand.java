package io.agentharness.cli;

import picocli.CommandLine.Command;

import java.util.List;
import java.util.concurrent.Callable;

/** 发消息模块。接 servlet 后就是两条 SSE 加一个历史拉取接口。 */
@Command(name = "egress", mixinStandardHelpOptions = true,
        description = "发消息模块：两条流的建连与推送、历史拉取")
public final class EgressCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        int code = new ModuleBriefing(
                "发消息模块",
                "P1（消息流）/ P4（历史拉取）/ P5（控制流）",
                List.of(
                        "消息流：建连后从 outbox 窗口起点全量重放，不解析任何位置参数",
                        "控制流：下发含 ctrlId 水位的快照，再从水位之后重放 ctrl-stream",
                        "历史拉取：GET /sessions/{sid}/messages?since=&limit=，直读消息表",
                        "ctrl-stream 用 200ms 非阻塞轮询，禁止 XREAD BLOCK"),
                List.of(ModuleBriefing.outbox(), ModuleBriefing.ctrlStream(),
                        ModuleBriefing.state(), "消息表（只读）"),
                List.of("无"),
                List.of(
                        "INV-12 阻塞命令独占连接，Redis 连接数会随 SSE 连接数线性放大",
                        "outbox 不用消费组：那是扇出语义，用了之后多端登录各拿一半事件")).print();
        System.out.println("  库已实现（agent-comm/egress），会话模式即通过它读取两条流。");
        System.out.println("  独立进程形态待 HTTP 门面 —— CLI 下它没有可服务的外部接口。");
        return code;
    }
}
