package com.agenticrag.dataanalysis;

/**
 * SQL 安全校验异常：信息面向用户脱敏，不泄露库/表/堆栈等内部细节。
 */
public class SqlSafetyException extends RuntimeException {
    public SqlSafetyException(String message) {
        super(message);
    }
}
