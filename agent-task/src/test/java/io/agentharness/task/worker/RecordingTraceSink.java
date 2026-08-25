package io.agentharness.task.worker;

import io.agentharness.trace.TraceSink;
import io.agentharness.trace.TraceStage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 把追踪收进列表，供断言。
 *
 * <p>用 {@link CopyOnWriteArrayList}：追踪会从多个 Reactor 线程同时进来，
 * 普通 ArrayList 在这里会偶发丢条目 —— 而那种失败看起来像是埋点漏了，
 * 排查方向完全是错的。
 */
final class RecordingTraceSink implements TraceSink {

    record Entry(TraceStage stage, String sessionId, String detail) {
    }

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void emit(TraceStage stage, String sessionId, String detail) {
        entries.add(new Entry(stage, sessionId, detail));
    }

    List<Entry> entries() {
        return List.copyOf(entries);
    }

    List<TraceStage> stages() {
        return entries.stream().map(Entry::stage).toList();
    }

    List<Entry> of(TraceStage stage) {
        return entries.stream().filter(e -> e.stage() == stage).toList();
    }
}
