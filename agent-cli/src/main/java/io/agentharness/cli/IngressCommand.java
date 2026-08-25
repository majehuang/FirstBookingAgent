package io.agentharness.cli;

import picocli.CommandLine.Command;

import java.util.List;
import java.util.concurrent.Callable;

/** 收消息模块。接 servlet 后就是 {@code POST /sessions/{sid}/messages} 的处理器。 */
@Command(name = "ingress", mixinStandardHelpOptions = true,
        description = "收消息模块：鉴权、校验、写 inbox+ready、准入限流")
public final class IngressCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        int code = new ModuleBriefing(
                "收消息模块",
                "P1（投递）/ P3（顺序与重试）/ P6（准入限流）",
                List.of(
                        "鉴权与参数校验，instructionId 幂等键校验",
                        "投递：先 XADD inbox，后 XADD ready；两条都成功才回 202",
                        "准入限流：唤醒队列深度超阈值返回 503 + Retry-After"),
                List.of(ModuleBriefing.ready() + "（判队列深度）"),
                List.of(ModuleBriefing.inbox(), ModuleBriefing.ready()),
                List.of(
                        "INV-1 顺序不可颠倒；未收到回执时客户端带同一 instructionId 重试",
                        "INV-9 所有 key 走 KeyNamespace，分片数 256 永不修改")).print();
        System.out.println("  库已实现（agent-comm/ingress），会话模式即通过它投递。");
        System.out.println("  独立进程形态待 HTTP 门面 —— CLI 下它没有可服务的外部接口。");
        return code;
    }
}
