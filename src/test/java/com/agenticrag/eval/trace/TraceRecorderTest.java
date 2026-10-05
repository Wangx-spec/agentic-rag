package com.agenticrag.eval.trace;

import com.agenticrag.eval.EvalProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TraceRecorderTest {

    @AfterEach
    void tearDown() {
        EvalTraceContext.clear();
    }

    @Test
    void recordsAndFlushesWhenEnabledAndContextExists() {
        RunTraceRepository repository = mock(RunTraceRepository.class);
        TraceRecorder recorder = new TraceRecorder(repository, enabledProps());
        EvalTraceContext.set("da-r1", "da-001");

        recorder.recordIntent(0, "DATA_ANALYSIS");
        recorder.flush();

        verify(repository).batchInsert(org.mockito.ArgumentMatchers.<List<TraceEvent>>argThat(events ->
                events.size() == 1
                        && "da-r1".equals(events.get(0).runId())
                        && "intent".equals(events.get(0).eventType())
                        && "DATA_ANALYSIS".equals(events.get(0).routedIntent())
        ));
    }

    @Test
    void disabledRecorderDoesNotTouchRepository() {
        RunTraceRepository repository = mock(RunTraceRepository.class);
        TraceRecorder recorder = new TraceRecorder(repository, new EvalProperties());
        EvalTraceContext.set("da-r1", "da-001");

        recorder.recordIntent(0, "DATA_ANALYSIS");
        recorder.flush();

        verify(repository, never()).batchInsert(anyList());
    }

    @Test
    void flushIsFailOpenWhenRepositoryThrows() {
        RunTraceRepository repository = mock(RunTraceRepository.class);
        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(repository).batchInsert(anyList());
        TraceRecorder recorder = new TraceRecorder(repository, enabledProps());
        EvalTraceContext.set("da-r1", "da-001");

        recorder.recordIntent(0, "DATA_ANALYSIS");

        assertDoesNotThrow(recorder::flush);
    }

    private EvalProperties enabledProps() {
        EvalProperties props = new EvalProperties();
        props.getTrace().setEnabled(true);
        return props;
    }
}
