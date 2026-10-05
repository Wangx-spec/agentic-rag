package com.agenticrag.eval.trace;

import com.agenticrag.agent.EvidenceRegistry;
import com.agenticrag.eval.EvalProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class TraceRecorder {

    private final RunTraceRepository repository;
    private final EvalProperties evalProperties;
    private final ThreadLocal<List<TraceEvent>> buffer = ThreadLocal.withInitial(ArrayList::new);

    public TraceRecorder(RunTraceRepository repository, EvalProperties evalProperties) {
        this.repository = repository;
        this.evalProperties = evalProperties;
    }

    public void record(TraceEvent event) {
        if (!enabled() || event == null || EvalTraceContext.current() == null) {
            return;
        }
        try {
            buffer.get().add(truncate(event));
        } catch (Exception e) {
            log.warn("记录评测轨迹失败，已忽略", e);
        }
    }

    public void recordIntent(int turn, String routedIntent) {
        EvalTraceContext ctx = EvalTraceContext.current();
        if (ctx == null) {
            return;
        }
        record(new TraceEvent(ctx.runId(), ctx.questionId(), turn, "intent",
                null, null, null, List.of(), List.of(), routedIntent, null, null));
    }

    public void recordToolCall(int turn, String toolName, String args, String resultSummary) {
        EvalTraceContext ctx = EvalTraceContext.current();
        if (ctx == null) {
            return;
        }
        record(new TraceEvent(ctx.runId(), ctx.questionId(), turn, "tool_call",
                toolName, args, resultSummary, List.of(), List.of(), null, null, estimateTokens(args, resultSummary)));
    }

    public void recordEvidence(int turn, EvidenceRegistry.Evidence evidence) {
        EvalTraceContext ctx = EvalTraceContext.current();
        if (ctx == null || evidence == null) {
            return;
        }
        record(new TraceEvent(ctx.runId(), ctx.questionId(), turn, "evidence",
                null, null, evidence.summary(), List.of(evidence.id()), List.of(toEvalType(evidence.type())),
                null, null, estimateTokens(evidence.summary())));
    }

    public void recordCritic(int turn, String verdict, String reason) {
        EvalTraceContext ctx = EvalTraceContext.current();
        if (ctx == null) {
            return;
        }
        record(new TraceEvent(ctx.runId(), ctx.questionId(), turn, "critic",
                null, null, reason, List.of(), List.of(), null, verdict, estimateTokens(reason)));
    }

    public void recordBudget(int turn, String summary) {
        EvalTraceContext ctx = EvalTraceContext.current();
        if (ctx == null) {
            return;
        }
        record(new TraceEvent(ctx.runId(), ctx.questionId(), turn, "budget",
                null, null, summary, List.of(), List.of(), null, null, estimateTokens(summary)));
    }

    public void recordFinal(int turn, String answer, List<EvidenceRegistry.Evidence> evidences) {
        EvalTraceContext ctx = EvalTraceContext.current();
        if (ctx == null) {
            return;
        }
        List<EvidenceRegistry.Evidence> safe = evidences == null ? List.of() : evidences;
        record(new TraceEvent(ctx.runId(), ctx.questionId(), turn, "final",
                null, null, answer,
                safe.stream().map(EvidenceRegistry.Evidence::id).toList(),
                safe.stream().map(e -> toEvalType(e.type())).toList(),
                null, null, estimateTokens(answer)));
    }

    public void flush() {
        if (!enabled()) {
            buffer.remove();
            return;
        }
        List<TraceEvent> events = new ArrayList<>(buffer.get());
        buffer.remove();
        if (events.isEmpty()) {
            return;
        }
        try {
            repository.batchInsert(events);
        } catch (Exception e) {
            log.warn("评测轨迹落库失败，已按 fail-open 忽略", e);
        }
    }

    public boolean enabled() {
        return evalProperties.getTrace() != null && evalProperties.getTrace().isEnabled();
    }

    private TraceEvent truncate(TraceEvent event) {
        int max = Math.max(1, evalProperties.getTrace().getSummaryMaxChars());
        return new TraceEvent(
                event.runId(),
                event.questionId(),
                event.turn(),
                event.eventType(),
                event.toolName(),
                truncate(event.toolArgsSummary(), max),
                truncate(event.resultSummary(), max),
                event.evidenceIds(),
                event.evidenceTypes(),
                event.routedIntent(),
                event.criticVerdict(),
                event.tokensUsed()
        );
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }

    private Integer estimateTokens(String... values) {
        int chars = 0;
        if (values != null) {
            for (String value : values) {
                chars += value == null ? 0 : value.length();
            }
        }
        return chars == 0 ? 0 : Math.max(1, (int) Math.ceil(chars / 1.5));
    }

    private String toEvalType(EvidenceRegistry.EvidenceType type) {
        if (type == null) {
            return "unknown";
        }
        return switch (type) {
            case DOCUMENT_CHUNK -> "document";
            case SQL_RESULT -> "sql";
            case MEMORY -> "memory";
        };
    }
}
