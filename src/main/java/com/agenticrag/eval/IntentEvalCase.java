package com.agenticrag.eval;

import com.agenticrag.intent.Intent;

public record IntentEvalCase(
        String id,
        String question,
        Intent expectedIntent
) {
}
