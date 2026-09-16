package com.agenticrag.service;

import com.agenticrag.intent.Intent;
import com.agenticrag.rag.retrieve.RetrievedChunk;

import java.util.List;

public record ChatResult(
        String answer,
        List<RetrievedChunk> sources,
        ChatMode executedMode,
        Intent routedIntent,
        List<ToolInvocation> toolInvocations,
        boolean degraded
) {
}
