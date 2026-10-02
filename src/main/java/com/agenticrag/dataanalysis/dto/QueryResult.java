package com.agenticrag.dataanalysis.dto;

import java.util.List;

/**
 * 只读 SQL 查询结果结构。
 *
 * @param columns    列名（顺序与 rows 对齐）
 * @param rows       数据行（Object 可为 null）
 * @param truncated  是否因行数上限被截断
 * @param totalRows  实际命中总行数（截断时 > rows.size()）
 */
public record QueryResult(
        List<String> columns,
        List<List<Object>> rows,
        boolean truncated,
        int totalRows
) {

    private static final int MAX_CELL_WIDTH = 50;

    /** 渲染为 Markdown 表格（给 LLM 观察与前端兜底展示），截断时追加标注。 */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", columns)).append(" |\n");
        sb.append("|").append(" --- |".repeat(columns.size())).append("\n");
        for (List<Object> row : rows) {
            sb.append("| ");
            for (Object cell : row) {
                sb.append(renderCell(cell)).append(" | ");
            }
            sb.append("\n");
        }
        if (truncated) {
            sb.append("\n（已截断，共 ").append(totalRows).append(" 行，仅展示前 ")
                    .append(rows.size()).append(" 行）");
        }
        return sb.toString();
    }

    private String renderCell(Object cell) {
        if (cell == null) {
            return "";
        }
        String s = cell.toString().replace("|", "\\|").replace("\n", " ");
        if (s.length() > MAX_CELL_WIDTH) {
            return s.substring(0, MAX_CELL_WIDTH) + "…";
        }
        return s;
    }
}
