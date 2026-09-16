package com.agenticrag.eval;

import com.agenticrag.intent.Intent;
import com.agenticrag.service.ChatMode;

import java.util.List;

public record EvalCase(
        String id,
        String question,
        String referenceAnswer,
        List<Long> sourceChunkIds,
        String expectTool,
        Intent expectedIntent,
        boolean expectRefusal,
        ChatMode mode
) {
}
