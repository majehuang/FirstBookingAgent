package io.agentharness.cli;

import picocli.CommandLine.Command;

import java.util.List;
import java.util.concurrent.Callable;

/** 任务控制模块。每个 pod 一个实例，整套设计里复杂度最集中的地方。 */
@Command(name = "dispatcher", mixinStandardHelpOptions = true,
        description = "任务控制模块：ready 消费、consumer 生命周期、回收清理、在飞上限")
public final class DispatcherCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        return new ModuleBriefing(
                "任务控制模块",
                "P1（简易消费循环）/ P3（消费组、心跳、回收、清理、上限）",
                List.of(
                        "XREADGROUP 消费唤醒队列，认领数量 = 空闲槽位数",
                        "consumer 名 = pod 名，不加时间戳后缀",
                        "持牌期 XCLAIM … JUSTID 心跳，与 lease 续租同频 10s",
                        "XAUTOCLAIM 回收：启动一次 + 每 30s，MIN-IDLE-TIME 60000，游标循环至 0-0",
                        "死 consumer 清理：先 XAUTOCLAIM 捞走工作，再对 pending=0 的 DELCONSUMER"),
                List.of(ModuleBriefing.ready() + "（消费组 workers）"),
                List.of(ModuleBriefing.lease(), ModuleBriefing.ready() + "（XACK）"),
                List.of(
                        "INV-2b Redis 没有 PEL 自动回队，不跑 XAUTOCLAIM 就是静默永久卡死",
                        "INV-2c 没有心跳，MIN-IDLE-TIME 只能设成大于最长 turn，恢复时延变分钟级",
                        "INV-2d 有 pending 时 DELCONSUMER 会连同条目一起销毁，工作无声蒸发",
                        "唤醒队列用 MAXLEN ~ 100000：XTRIM 不看 PEL，裁小了会在积压时静默丢工作")).print();
    }
}
