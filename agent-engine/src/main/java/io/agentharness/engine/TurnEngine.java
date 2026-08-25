package io.agentharness.engine;

import io.agentharness.engine.rich.RichMessageRegistry;
import io.agentharness.protocol.SessionRef;
import io.agentscope.core.agent.Event;
import reactor.core.publisher.Flux;

/**
 * 一轮推理的执行者。
 *
 * <p>抽成接口有两个目的：
 * <ul>
 *   <li>{@link AgentScopeBackend} 不再依赖具体的 AgentScope 装配，
 *       于是"多个 session 共用同一个引擎实例"这件事可以脱离真实模型来断言</li>
 *   <li>将来换引擎（或为不同租户装配不同引擎）时，改动止步于这个接口</li>
 * </ul>
 *
 * <p><b>实现必须是无状态的</b>：一个实例服务所有 {@code (userId, sessionId)}，
 * 会话状态按需从 store 加载。这是 v2 agent 的模型，也是不需要
 * Agent Factory / Session Cache 那整层抽象的原因（开发规划 H 节）。
 */
public interface TurnEngine extends AutoCloseable {

    /**
     * 跑一轮推理。
     *
     * <p><b>调用方必须把返回的流 {@code subscribeOn(boundedElastic)}。</b>
     * AgentStateStore 是阻塞接口，配 PG 之后每轮推理都有阻塞 JDBC 落在订阅线程上；
     * 落在事件循环线程上会占死全 pod 的所有 session（INV-7）。
     */
    Flux<Event> stream(SessionRef session, String text);

    /**
     * 本引擎装配了哪些表达型工具的渲染器。
     *
     * <p>让注册表跟着引擎走，而不是让调用方另外传一份：
     * 渲染器与工具是同一次装配的产物，分开传就有装配不一致的可能 ——
     * 工具注册了、渲染器没注册，表现是卡片变成一行「⚒ 调用了发卡片的函数」。
     */
    default RichMessageRegistry renderers() {
        return RichMessageRegistry.empty();
    }

    /**
     * 打断当前推理。
     *
     * <p>只对<b>本进程内</b>正在跑的 turn 有效。跨节点打断要靠把 IMMEDIATE 指令写进 inbox，
     * 由持牌 pod 轮询扫到后调到这里（P5）。
     */
    void interrupt();

    @Override
    void close();
}
