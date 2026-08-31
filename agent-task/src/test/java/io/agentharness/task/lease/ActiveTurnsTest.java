package io.agentharness.task.lease;

import io.agentharness.protocol.SessionRef;
import io.agentharness.redis.LeaseGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 在飞表 —— 优雅停机能不能找到要交接的 turn，全看它。 */
class ActiveTurnsTest {

    private static ActiveTurns.Handle register(ActiveTurns turns, String sessionId, String token) {
        SessionRef session = SessionRef.of("u", sessionId);
        return turns.register(session,
                new LeaseGuard.Held(session, "key:" + sessionId, token),
                "1-0", new LeaseFence(sessionId));
    }

    @Test
    @DisplayName("登记与注销成对，注销后不再出现在快照里")
    void 登记与注销() {
        ActiveTurns turns = new ActiveTurns();

        ActiveTurns.Handle a = register(turns, "s-1", "t-1");
        register(turns, "s-2", "t-2");
        assertThat(turns.size()).isEqualTo(2);

        turns.unregister(a);

        assertThat(turns.size()).isEqualTo(1);
        assertThat(turns.snapshot())
                .extracting(h -> h.session().sessionId())
                .containsExactly("s-2");
    }

    @Test
    @DisplayName("键用 lease token 而不是 sessionId —— 同一 session 的两次持有不会互相挤掉")
    void 同一会话的两次持有各占一条() {
        ActiveTurns turns = new ActiveTurns();

        ActiveTurns.Handle old = register(turns, "s-1", "t-old");
        register(turns, "s-1", "t-new");

        // 用 sessionId 当键的话这里会是 1 —— 停机时就漏交接一个
        assertThat(turns.size()).isEqualTo(2);

        turns.unregister(old);
        assertThat(turns.snapshot()).extracting(h -> h.lease().token()).containsExactly("t-new");
    }

    @Test
    @DisplayName("快照是副本 —— 遍历它的同时那些 turn 正在陆续注销")
    void 快照不受后续注销影响() {
        ActiveTurns turns = new ActiveTurns();
        ActiveTurns.Handle a = register(turns, "s-1", "t-1");

        var snapshot = turns.snapshot();
        turns.unregister(a);

        assertThat(snapshot).hasSize(1);
        assertThat(turns.size()).isZero();
    }

    @Test
    @DisplayName("重复注销无害 —— doFinally 可能因异常与取消各走一次")
    void 重复注销无害() {
        ActiveTurns turns = new ActiveTurns();
        ActiveTurns.Handle a = register(turns, "s-1", "t-1");

        turns.unregister(a);
        turns.unregister(a);

        assertThat(turns.size()).isZero();
    }
}
