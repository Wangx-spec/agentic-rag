package com.agenticrag.tool.tools;

import com.agenticrag.tool.Tool;
import com.agenticrag.tool.ToolRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CalculatorTool implements Tool {

    private static final String NAME = "calculator";
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "expression": {
                  "type": "string",
                  "description": "仅包含整数、四则运算符 + - * / 和圆括号的数学表达式"
                }
              },
              "required": ["expression"]
            }
            """;

    private final ToolRegistry toolRegistry;

    @PostConstruct
    public void register() {
        toolRegistry.register(this);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "计算四则运算数学表达式，支持 + - * / 和圆括号";
    }

    @Override
    public String parametersSchema() {
        return SCHEMA;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        Object expressionObj = arguments.get("expression");
        if (expressionObj == null) {
            return "错误：缺少 expression 参数";
        }
        String expression = expressionObj.toString().replaceAll("\\s+", "");
        if (expression.isEmpty()) {
            return "错误：表达式为空";
        }
        try {
            double result = evaluate(expression);
            if (result == (long) result) {
                return String.valueOf((long) result);
            }
            return String.valueOf(result);
        } catch (Exception e) {
            return "计算错误：" + e.getMessage();
        }
    }

    private static double evaluate(String expression) {
        String postfix = toPostfix(expression);
        Deque<Double> stack = new ArrayDeque<>();
        StringBuilder number = new StringBuilder();

        for (int i = 0; i < postfix.length(); i++) {
            char c = postfix.charAt(i);
            if (Character.isDigit(c) || c == '.') {
                number.append(c);
            } else if (isOperator(c)) {
                if (!number.isEmpty()) {
                    stack.push(Double.parseDouble(number.toString()));
                    number.setLength(0);
                }
                double b = stack.pop();
                double a = stack.isEmpty() ? 0 : stack.pop();
                stack.push(apply(a, b, c));
            } else if (c == ' ') {
                if (!number.isEmpty()) {
                    stack.push(Double.parseDouble(number.toString()));
                    number.setLength(0);
                }
            }
        }
        if (!number.isEmpty()) {
            stack.push(Double.parseDouble(number.toString()));
        }
        return stack.isEmpty() ? 0 : stack.pop();
    }

    private static String toPostfix(String expression) {
        StringBuilder output = new StringBuilder();
        Deque<Character> stack = new ArrayDeque<>();

        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);

            if (Character.isDigit(c) || c == '.') {
                output.append(c);
            } else if (c == '(') {
                output.append(' ');
                stack.push(c);
            } else if (c == ')') {
                output.append(' ');
                while (!stack.isEmpty() && stack.peek() != '(') {
                    output.append(stack.pop()).append(' ');
                }
                stack.pop();
            } else if (isOperator(c)) {
                output.append(' ');
                while (!stack.isEmpty() && precedence(stack.peek()) >= precedence(c)) {
                    output.append(stack.pop()).append(' ');
                }
                stack.push(c);
            }
        }
        while (!stack.isEmpty()) {
            output.append(' ').append(stack.pop());
        }
        return output.toString();
    }

    private static boolean isOperator(char c) {
        return c == '+' || c == '-' || c == '*' || c == '/';
    }

    private static int precedence(char op) {
        return switch (op) {
            case '+' -> 1;
            case '-' -> 1;
            case '*' -> 2;
            case '/' -> 2;
            default -> 0;
        };
    }

    private static double apply(double a, double b, char op) {
        return switch (op) {
            case '+' -> a + b;
            case '-' -> a - b;
            case '*' -> a * b;
            case '/' -> {
                if (b == 0) {
                    throw new ArithmeticException("除数不能为零");
                };
                yield a / b;
            }
            default -> throw new IllegalArgumentException("未知运算符：" + op);
        };
    }
}