package com.agenticrag.dataanalysis.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QueryResult.toMarkdown 渲染 + 截断标注单测（对应 T7 验证）。
 */
class QueryResultTest {

    @Test
    void rendersHeaderSeparatorAndRows() {
        QueryResult r = new QueryResult(
                List.of("platform", "cnt"),
                List.of(List.of("淘宝", 3L), List.of("天猫", 5L)),
                false, 2);

        String md = r.toMarkdown();

        assertTrue(md.startsWith("| platform | cnt |"));
        assertTrue(md.contains("| --- | --- |"));
        assertTrue(md.contains("| 淘宝 | 3 |"));
        assertTrue(md.contains("| 天猫 | 5 |"));
    }

    @Test
    void rendersNullAsEmptyCell() {
        QueryResult r = new QueryResult(
                List.of("platform", "comment"),
                List.of(java.util.Arrays.asList("京东", null)),
                false, 1);

        String md = r.toMarkdown();

        assertTrue(md.contains("| 京东 |  |"));
    }

    @Test
    void escapesPipeAndFlattensNewlineInCell() {
        QueryResult r = new QueryResult(
                List.of("remark"),
                List.of(List.of("a|b\nc")),
                false, 1);

        String md = r.toMarkdown();

        assertTrue(md.contains("| a\\|b c |"));
        assertFalse(md.contains("a|b\nc"));
    }

    @Test
    void truncatesOverlongCellTo50Chars() {
        String cell = "x".repeat(60);
        QueryResult r = new QueryResult(
                List.of("remark"),
                List.of(List.of(cell)),
                false, 1);

        String md = r.toMarkdown();

        assertFalse(md.contains(cell));
        assertTrue(md.contains("x".repeat(50) + "…"));
    }

    @Test
    void appendsTruncationNoteWhenTruncated() {
        QueryResult r = new QueryResult(
                List.of("platform"),
                List.of(List.of("淘宝"), List.of("天猫"), List.of("京东")),
                true, 120);

        String md = r.toMarkdown();

        assertTrue(md.contains("已截断"));
        assertTrue(md.contains("共 120 行"));
        assertTrue(md.contains("仅展示前 3 行"));
    }

    @Test
    void omitsTruncationNoteWhenNotTruncated() {
        QueryResult r = new QueryResult(
                List.of("platform"),
                List.of(List.of("淘宝")),
                false, 1);

        String md = r.toMarkdown();

        assertFalse(md.contains("已截断"));
    }
}
