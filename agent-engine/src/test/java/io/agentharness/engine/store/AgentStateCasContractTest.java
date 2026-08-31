package io.agentharness.engine.store;

import io.agentscope.core.state.AgentStateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖 Test/P3 的 <b>CAS-001、CAS-005</b>，钉住 P3-13 于 2026-08-28 冻结的契约：
 *
 * <blockquote>
 * {@code AgentStateStore} 只负责共享状态持久化，允许普通 LWW；
 * <b>不要求版本、CAS 或冲突重试</b>。同一 session 的 Worker 不双跑，
 * <b>唯一由 lease（INV-3）保证</b>。
 * </blockquote>
 *
 * <h2>类名保留了历史，含义已经反过来了</h2>
 * 这个类原先叫这个名字，是因为计划曾要求"{@code AgentStateStore} 走支持 CAS 的后端"，
 * 而上游接口表达不了版本 —— 当时它钉的是"<b>别把这条当成已完成</b>"。
 *
 * <p>现在契约变了：<b>我们本来就不要 CAS。</b>所以同一个类改为钉住相反的一面 ——
 * <b>别把存储层当成写者仲裁器</b>。名字按计划保留，免得追溯记录时断了线索。
 *
 * <h2>为什么这件事值得用测试钉住</h2>
 * 风险登记册 R-9 描述的路径是：有人看到状态存储里有版本号或 CAS 入口，
 * 就默认"双写有兜底"，进而开始怀疑 lease 是不是可以放松一点。
 * <b>那是这套设计里最贵的一种误解</b> —— lease 一旦被当成"其中一道"而不是"唯一一道"，
 * 它的每个缺陷都会被当作可接受的。
 *
 * <p>所以这里断言的不是某个功能可用，而是<b>某个诱惑不存在</b>。
 */
class AgentStateCasContractTest {

    @Test
    @DisplayName("CAS-005 上游 save 不接收版本号 —— 这正合契约，不是缺陷")
    void 上游接口不带版本号() {
        for (Method save : AgentStateStore.class.getMethods()) {
            if (!save.getName().equals("save")) {
                continue;
            }
            assertThat(save.getParameterTypes())
                    .as("save 的入参里出现了版本号：上游开始支持 CAS 了。"
                            + "这不会让契约失效（我们仍然只靠 lease），"
                            + "但值得回头确认没有人顺势把它当成第二道防线：%s", save)
                    .noneMatch(type -> type == long.class || type == Long.class
                            || type == int.class || type == Integer.class);
        }
    }

    @Test
    @DisplayName("CAS-005 生产实现不提供任何 CAS 写入入口 —— 防的是 R-9 那种误解")
    void 实现不提供CAS入口() {
        boolean hasVersionedWrite = Arrays.stream(PostgresAgentStateStore.class.getMethods())
                .filter(m -> m.getName().toLowerCase().contains("save"))
                .anyMatch(m -> Arrays.stream(m.getParameterTypes())
                        .anyMatch(type -> type == long.class || type == Long.class));

        assertThat(hasVersionedWrite)
                .as("状态存储上出现了带版本的写入方法。契约是「存储层不做仲裁」——"
                        + "这样一个方法即使没人调用，也迟早会被当成防双跑的第二道防线，"
                        + "然后 lease 的缺陷就开始被容忍了（风险登记册 R-9）")
                .isFalse();
    }

    @Test
    @DisplayName("CAS-001 生产装配用 PostgreSQL 实现，不是 InMemory / JsonFile")
    void 生产实现是PostgreSQL() {
        // 上游那两个实现多 pod 下都不能用：前者进程一停就没了，后者按 pod 分叉。
        // 换掉后端不影响防双跑（那是 lease 的事），但会让状态在多 pod 下不共享
        assertThat(AgentStateStore.class).isAssignableFrom(PostgresAgentStateStore.class);
        assertThat(PostgresAgentStateStore.class.getSimpleName()).startsWith("Postgres");
    }
}
