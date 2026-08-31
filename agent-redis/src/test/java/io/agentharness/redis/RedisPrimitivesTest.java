package io.agentharness.redis;

import io.agentharness.protocol.SessionRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 不需要 Redis 的那部分：载荷编解码、令牌、摘牌返回码。
 *
 * <p>与 {@code UnleaseScriptIntegrationTest} 分开的理由是这些断言<b>不该</b>被跳过。
 * 上面那些用例默认关闭（没有 Redis 就不跑），而 {@code fromCode} 认错一个返回码
 * 这种错误，恰恰是在没人跑集成测试的那次提交里溜进去的。
 */
class RedisPrimitivesTest {

    // ---------- StreamPayload ----------

    @Test
    @DisplayName("整个对象序列化成一个字段，往返保真")
    void 载荷往返() {
        ReadyToken token = new ReadyToken("u-1", "s-1");

        Map<String, String> body = StreamPayload.of(token);

        assertThat(body).containsOnlyKeys(StreamPayload.FIELD);
        assertThat(StreamPayload.read(body, ReadyToken.class)).isEqualTo(token);
    }

    @Test
    @DisplayName("缺字段时报出能定位问题的错，而不是返回 null 让调用方继续跑")
    void 缺字段时明确报错() {
        // SessionWorker 与 ReadyDispatcher 都依赖这里抛异常来把坏条目跳过去；
        // 返回 null 的话，坏条目会一路飘到业务逻辑里才炸
        assertThatThrownBy(() -> StreamPayload.read(Map.of(), ReadyToken.class))
                .isInstanceOf(RedisException.class)
                .hasMessageContaining(StreamPayload.FIELD);

        assertThatThrownBy(() -> StreamPayload.read(null, ReadyToken.class))
                .isInstanceOf(RedisException.class);

        Map<String, String> blank = new HashMap<>();
        blank.put(StreamPayload.FIELD, "   ");
        assertThatThrownBy(() -> StreamPayload.read(blank, ReadyToken.class))
                .isInstanceOf(RedisException.class);
    }

    // ---------- ReadyToken ----------

    @Test
    @DisplayName("令牌带上 userId —— ready 是全局队列，没有别处能补出用户身份")
    void 令牌携带完整会话标识() {
        SessionRef session = SessionRef.of("u-7", "s-7");

        ReadyToken token = ReadyToken.of(session);

        assertThat(token.userId()).isEqualTo("u-7");
        assertThat(token.sessionId()).isEqualTo("s-7");
        assertThat(token.toSession()).isEqualTo(session);
    }

    // ---------- UnleaseOutcome ----------

    @Test
    @DisplayName("返回码与枚举一一对应，且只有摘牌成功允许交差")
    void 摘牌返回码映射() {
        assertThat(UnleaseOutcome.fromCode(1)).isEqualTo(UnleaseOutcome.UNLEASED);
        assertThat(UnleaseOutcome.fromCode(0)).isEqualTo(UnleaseOutcome.WORK_PENDING);
        assertThat(UnleaseOutcome.fromCode(-1)).isEqualTo(UnleaseOutcome.NOT_HOLDER);

        assertThat(UnleaseOutcome.UNLEASED.mayAck()).isTrue();
        // 这两条是 INV-4：交差提前一步，崩溃后就没有任何线索
        assertThat(UnleaseOutcome.WORK_PENDING.mayAck()).isFalse();
        assertThat(UnleaseOutcome.NOT_HOLDER.mayAck()).isFalse();
    }

    @Test
    @DisplayName("未知返回码当场炸 —— 静默当成成功会直接违反 INV-4")
    void 未知返回码不被吞掉() {
        assertThatThrownBy(() -> UnleaseOutcome.fromCode(42))
                .isInstanceOf(RedisException.class)
                .hasMessageContaining("42");
    }

    // ---------- ScriptRegistry ----------

    @Test
    @DisplayName("NOSCRIPT 能从嵌套异常里认出来 —— Lettuce 会把它包一层")
    void 识别NOSCRIPT() {
        assertThat(ScriptRegistry.isNoScript(
                new RuntimeException("NOSCRIPT No matching script"))).isTrue();
        assertThat(ScriptRegistry.isNoScript(
                new RuntimeException("外层", new RuntimeException("NOSCRIPT ...")))).isTrue();

        assertThat(ScriptRegistry.isNoScript(new RuntimeException("WRONGTYPE"))).isFalse();
        assertThat(ScriptRegistry.isNoScript(new RuntimeException((String) null))).isFalse();
    }

    @Test
    @DisplayName("未加载就执行时报出可操作的错，而不是空指针")
    void 未加载脚本时明确报错() {
        RedisException error = new RedisException("连接失败", new RuntimeException());

        assertThat(error).hasMessageContaining("连接失败");
        assertThat(error.getCause()).isNotNull();
    }

    // ---------- StreamLimits ----------

    @Test
    @DisplayName("三条 Stream 的裁剪上限集中在一处（TRM-001）")
    void 裁剪上限集中定义() {
        assertThat(StreamLimits.READY_MAX_LEN).isEqualTo(100_000L);
        assertThat(StreamLimits.INBOX_MAX_LEN).isEqualTo(10_000L);
        assertThat(StreamLimits.OUTBOX_MAX_LEN).isEqualTo(10_000L);

        assertThat(StreamLimits.ready()).isNotNull();
        assertThat(StreamLimits.inbox()).isNotNull();
    }
}
