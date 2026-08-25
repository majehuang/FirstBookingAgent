package io.agentharness.trace;

/** 关闭状态。单例，因为它没有任何状态。 */
enum NoopTraceSink implements TraceSink {

    INSTANCE;

    @Override
    public void emit(TraceStage stage, String sessionId, String detail) {
        // 故意留空
    }

    @Override
    public boolean enabled() {
        return false;
    }
}
