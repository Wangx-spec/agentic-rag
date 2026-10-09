package com.agenticrag.dataanalysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;

/**
 * SqlSafetyGuard 全规则单测（对应 T6 验证）：
 * 合法 / 非法前缀 / 多语句 / 尾分号 / 字面量与注释不误杀 / 词边界。
 */
class SqlSafetyGuardTest {

    private final SqlSafetyGuard guard = new SqlSafetyGuard();

    /* ===== 合法查询 ===== */

    @Test
    void allowsPlainSelect() {
        assertDoesNotThrow(() -> guard.validate("SELECT * FROM orders"));
    }

    @Test
    void allowsWithCte() {
        assertDoesNotThrow(() ->
                guard.validate("WITH t AS (SELECT platform, COUNT(*) AS cnt FROM orders GROUP BY platform) SELECT * FROM t"));
    }

    @Test
    void allowsLowercasePrefix() {
        assertDoesNotThrow(() -> guard.validate("select count(*) from orders"));
    }

    @Test
    void allowsLeadingWhitespaceAndNewline() {
        assertDoesNotThrow(() -> guard.validate("  \n\t SELECT 1"));
    }

    @Test
    void allowsSingleTrailingSemicolon() {
        assertDoesNotThrow(() -> guard.validate("SELECT 1;"));
    }

    /* ===== 空与非法前缀 ===== */

    @Test
    void rejectsNullSql() {
        assertThrows(SqlSafetyException.class, () -> guard.validate(null));
    }

    @Test
    void rejectsBlankSql() {
        assertThrows(SqlSafetyException.class, () -> guard.validate("   "));
    }

    @Test
    void rejectsInsertPrefix() {
        assertThrows(SqlSafetyException.class, () -> guard.validate("INSERT INTO orders VALUES (1)"));
    }

    @Test
    void rejectsUpdatePrefix() {
        assertThrows(SqlSafetyException.class, () -> guard.validate("UPDATE orders SET status = 'PAID'"));
    }

    @Test
    void rejectsDeletePrefix() {
        assertThrows(SqlSafetyException.class, () -> guard.validate("DELETE FROM orders"));
    }

    @Test
    void rejectsCreatePrefix() {
        assertThrows(SqlSafetyException.class, () -> guard.validate("CREATE TABLE hack (id int)"));
    }

    @Test
    void rejectsNonWhitelistedPrefix() {
        assertThrows(SqlSafetyException.class, () -> guard.validate("EXPLAIN SELECT * FROM orders"));
    }

    /* ===== 多语句 / 分号 ===== */

    @Test
    void rejectsMultipleStatements() {
        assertThrows(SqlSafetyException.class, () -> guard.validate("SELECT 1; SELECT 2"));
    }

    @Test
    void rejectsTrailingSecondStatement() {
        assertThrows(SqlSafetyException.class, () -> guard.validate("SELECT * FROM orders; DELETE FROM orders"));
    }

    @Test
    void rejectsDoubleTrailingSemicolon() {
        assertThrows(SqlSafetyException.class, () -> guard.validate("SELECT 1;;"));
    }

    @Test
    void allowsSemicolonInsideStringLiteral() {
        assertDoesNotThrow(() -> guard.validate("SELECT * FROM orders WHERE remark = '包含;分号的备注'"));
    }

    /* ===== 字面量与注释不误杀 ===== */

    @Test
    void allowsDangerousWordsInsideStringLiteral() {
        assertDoesNotThrow(() ->
                guard.validate("SELECT * FROM orders WHERE remark = 'ignore this DROP TABLE hint'"));
    }

    @Test
    void allowsEscapedQuoteInsideStringLiteral() {
        assertDoesNotThrow(() -> guard.validate("SELECT * FROM orders WHERE remark = 'it''s UPDATE nothing'"));
    }

    @Test
    void allowsDangerousWordsInLineComment() {
        assertDoesNotThrow(() -> guard.validate("SELECT * FROM orders -- drop table xxx; delete from yyy\n"));
    }

    @Test
    void allowsLeadingLineComment() {
        assertDoesNotThrow(() -> guard.validate("-- 查询订单\nSELECT 1"));
    }

    @Test
    void allowsLeadingBlockComment() {
        assertDoesNotThrow(() -> guard.validate("/* UPDATE 説明 */ SELECT 1"));
    }

    @Test
    void allowsDangerousWordsInBlockComment() {
        assertDoesNotThrow(() -> guard.validate("SELECT /* truncate; grant */ * FROM orders"));
    }

    @Test
    void allowsDoubleQuotedIdentifierContainingKeyword() {
        assertDoesNotThrow(() -> guard.validate("SELECT \"update\" FROM \"delete\""));
    }

    @Test
    void allowsSemicolonInLineComment() {
        assertDoesNotThrow(() -> guard.validate("SELECT 1 -- select; drop\n"));
    }

    /* ===== 词边界：不误杀、不漏杀 ===== */

    @Test
    void doesNotKillKeywordPrefixedIdentifiers() {
        assertDoesNotThrow(() ->
                guard.validate("SELECT selected, settings, updated_at, created_at FROM orders"));
    }

    @Test
    void rejectsDangerousKeywordsAfterSelectPrefix() {
        assertThrows(SqlSafetyException.class, () ->
                guard.validate("WITH moved AS (DELETE FROM orders RETURNING *) SELECT * FROM moved"));
    }

    @Test
    void rejectsIntoClause() {
        assertThrows(SqlSafetyException.class, () ->
                guard.validate("SELECT * INTO new_table FROM orders"));
    }

    @Test
    void rejectsPgSleepFunction() {
        assertThrows(SqlSafetyException.class, () -> guard.validate("SELECT pg_sleep(10)"));
    }

    @Test
    void rejectsGrant() {
        assertThrows(SqlSafetyException.class, () -> guard.validate("GRANT ALL ON orders TO public"));
    }

    @Test
    void rejectsCommentStatement() {
        assertThrows(SqlSafetyException.class, () -> guard.validate("COMMENT ON TABLE orders IS 'x'"));
    }

    @Test
    void rejectsSetStatement() {
        assertThrows(SqlSafetyException.class, () -> guard.validate("SET statement_timeout = 0"));
    }

    @Test
    void rejectsNonDemoTableWithRetryableUnknownTableException() {
        DemoSchemaService schema = mock(DemoSchemaService.class);
        when(schema.tableNamesLowercase()).thenReturn(Set.of("orders"));
        when(schema.availableTableList()).thenReturn("orders");
        SqlSafetyGuard guarded = new SqlSafetyGuard(schema);

        SqlSafetyGuard.UnknownTableException ex = assertThrows(SqlSafetyGuard.UnknownTableException.class,
                () -> guarded.validate("SELECT * FROM documents"));

        assertTrue(ex.getMessage().contains("表 documents 不存在"));
        assertTrue(ex.getMessage().contains("orders"));
    }

    @Test
    void allowsCteNameOutsideWhitelist() {
        DemoSchemaService schema = mock(DemoSchemaService.class);
        when(schema.tableNamesLowercase()).thenReturn(Set.of("orders"));
        SqlSafetyGuard guarded = new SqlSafetyGuard(schema);

        assertDoesNotThrow(() -> guarded.validate("""
                WITH t AS (
                    SELECT platform, COUNT(*) AS cnt FROM orders GROUP BY platform
                )
                SELECT * FROM t
                """));
    }
}
