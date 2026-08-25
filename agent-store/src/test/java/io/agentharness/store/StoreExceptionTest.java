package io.agentharness.store;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StoreExceptionTest {

    @Test
    void 默认不可重试_重试要显式声明() {
        assertThat(new StoreException("boom").retryable()).isFalse();
        assertThat(new StoreException("boom", new RuntimeException()).retryable()).isFalse();
    }

    @Test
    void 显式声明后可重试() {
        StoreException e = new StoreException("boom", new RuntimeException(), true);

        assertThat(e.retryable()).isTrue();
        assertThat(e).hasMessage("boom").hasCauseInstanceOf(RuntimeException.class);
    }
}
