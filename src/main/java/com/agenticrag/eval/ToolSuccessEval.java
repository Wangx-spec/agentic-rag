package com.agenticrag.eval;

import com.agenticrag.service.ChatResult;
import com.agenticrag.service.ToolInvocation;
import org.springframework.stereotype.Component;

@Component
public class ToolSuccessEval {

    public Result evaluate(ChatResult result, String expectedTool) {
        if (expectedTool == null || expectedTool.isBlank()) {
            return new Result(true, false);
        }
        if (result == null || result.toolInvocations() == null) {
            return new Result(false, false);
        }
        for (ToolInvocation invocation : result.toolInvocations()) {
            if (expectedTool.equals(invocation.toolName())) {
                boolean argumentsOk = invocation.arguments() != null && !invocation.arguments().isBlank();
                return new Result(argumentsOk, true);
            }
        }
        return new Result(false, false);
    }

    public record Result(boolean passed, boolean invoked) {
    }
}
