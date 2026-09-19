package com.agenticrag.rag.index;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.agenticrag.rag.index.TextAnalyzer.buildAndQuery;
import static com.agenticrag.rag.index.TextAnalyzer.buildIndexedContent;
import static com.agenticrag.rag.index.TextAnalyzer.buildOrQuery;
import static com.agenticrag.rag.index.TextAnalyzer.buildQueryTokens;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6 Phase 2.2 单测：停用词过滤 + Porter 词干归一 + 特殊 token 抽取 + AND/OR 查询构造。
 * 不依赖 Spring 上下文或 SQLite——TextAnalyzer 为纯静态工具类。
 */
class TextAnalyzerTest {

    // ==================== buildQueryTokens ====================

    @Test
    void stopWordsRemoved() {
        TextAnalyzer.QueryTokens tokens = buildQueryTokens("the quick brown fox is running");
        assertEquals(List.of("quick", "brown", "fox", "run"), tokens.normalTokens());
        assertTrue(tokens.specialTokens().isEmpty());
    }

    @Test
    void porterStemsEnglishWords() {
        TextAnalyzer.QueryTokens tokens = buildQueryTokens("running cats connected easily");
        // running→run (step1b), cats→cat (step1a), connected→connect (step1b), easily→easili (step1c)
        List<String> normal = tokens.normalTokens();
        assertTrue(normal.contains("run"));
        assertTrue(normal.contains("cat"));
        assertTrue(normal.contains("connect"));
        assertTrue(normal.contains("easili"));
    }

    @Test
    void chineseBigramPreserved() {
        TextAnalyzer.QueryTokens tokens = buildQueryTokens("你好世界检索增强");
        List<String> normal = tokens.normalTokens();
        assertTrue(normal.contains("你好"));
        assertTrue(normal.contains("好世"));
        assertTrue(normal.contains("世界"));
        assertTrue(normal.contains("界检"));
        assertTrue(normal.contains("检索"));
        assertTrue(normal.contains("索增"));
        assertTrue(normal.contains("增强"));
    }

    @Test
    void mixedChineseEnglish() {
        TextAnalyzer.QueryTokens tokens = buildQueryTokens("RAG 检索 the document");
        List<String> normal = tokens.normalTokens();
        // RAG→rag, 检+索=检索, 索=单独保留（下一个字符是 ASCII 空格，不构成 bigram）,
        // the=停用词过滤, document 不变（step4 "ment" 匹配但 measure 不足 → 直接 return，不走 "ent"）
        assertEquals(List.of("rag", "检索", "索", "document"), normal);
        assertTrue(normal.stream().noneMatch("the"::equals));
    }

    @Test
    void jiraIdExtractedAsSpecialToken() {
        TextAnalyzer.QueryTokens tokens = buildQueryTokens("fix CONF-1234 in production");
        assertEquals(List.of("conf1234"), tokens.specialTokens());
        // fix→fix, CONF→conf, 1234→1234, in=停用词, production→product (step4 ion→因结尾 t 而削去)
        assertEquals(List.of("fix", "conf", "1234", "product"), tokens.normalTokens());
    }

    @Test
    void dateLikeExtractedAsSpecialToken() {
        TextAnalyzer.QueryTokens tokens = buildQueryTokens("report for 2024-01-15 deadline");
        assertEquals(List.of("20240115"), tokens.specialTokens());
    }

    @Test
    void longCodeExtractedAsSpecialToken() {
        TextAnalyzer.QueryTokens tokens = buildQueryTokens("reference ABC123DEF in document");
        assertEquals(List.of("abc123def"), tokens.specialTokens());
    }

    @Test
    void specialTokenDeduplication() {
        TextAnalyzer.QueryTokens tokens = buildQueryTokens("CONF-1234 and CONF-1234 again");
        assertEquals(List.of("conf1234"), tokens.specialTokens());
    }

    @Test
    void nullInputReturnsEmpty() {
        assertTrue(buildQueryTokens(null).isEmpty());
    }

    @Test
    void blankInputReturnsEmpty() {
        assertTrue(buildQueryTokens("  ").isEmpty());
    }

    @Test
    void allStopWordsAndBrokenSpecialsInNormal() {
        // "the and or CONF-1234"：停用词全过滤，但 CONF-1234 被 ASCII 分词拆成 "conf"+"1234" 入 normal
        TextAnalyzer.QueryTokens tokens = buildQueryTokens("the and or CONF-1234");
        assertEquals(List.of("conf", "1234"), tokens.normalTokens());
        assertEquals(List.of("conf1234"), tokens.specialTokens());
        assertTrue(!tokens.isEmpty());
    }

    // ==================== buildAndQuery ====================

    @Test
    void andQueryAllTokensQuoted() {
        TextAnalyzer.QueryTokens tokens = buildQueryTokens("running fox CONF-1234");
        String query = buildAndQuery(tokens);
        // Should be: '"run" "fox" "conf1234"' (space-delimited = FTS5 implicit AND)
        assertTrue(query.contains("\"run\""));
        assertTrue(query.contains("\"fox\""));
        assertTrue(query.contains("\"conf1234\""));
    }

    @Test
    void andQueryQuoteEscapesDoubleQuote() {
        TextAnalyzer.QueryTokens tokens = new TextAnalyzer.QueryTokens(
                List.of("he\"llo"), List.of());
        String query = buildAndQuery(tokens);
        assertTrue(query.contains("\"he\"\"llo\""));
    }

    // ==================== buildOrQuery ====================

    @Test
    void orQueryNoSpecialsAllOR() {
        TextAnalyzer.QueryTokens tokens = new TextAnalyzer.QueryTokens(
                List.of("fox", "run"), List.of());
        String query = buildOrQuery(tokens);
        assertEquals("\"fox\" OR \"run\"", query);
    }

    @Test
    void orQuerySpecialsKeptAND() {
        TextAnalyzer.QueryTokens tokens = new TextAnalyzer.QueryTokens(
                List.of("fox", "run"), List.of("conf1234"));
        String query = buildOrQuery(tokens);
        // specials AND'ed, normals OR'ed in parens
        assertTrue(query.startsWith("\"conf1234\" AND ("));
        assertTrue(query.contains("\"fox\" OR \"run\""));
        assertTrue(query.endsWith(")"));
    }

    @Test
    void orQueryOnlySpecials() {
        TextAnalyzer.QueryTokens tokens = new TextAnalyzer.QueryTokens(
                List.of(), List.of("conf1234", "20240115"));
        String query = buildOrQuery(tokens);
        assertEquals("\"conf1234\" AND \"20240115\"", query);
    }

    // ==================== buildIndexedContent ====================

    @Test
    void indexedContentDeduplicatesNormalsAndSpecials() {
        String content = buildIndexedContent("the fox and the fox CONF-1234 CONF-1234");
        // normals: fox, fox, conf, 1234, conf, 1234 → dedup: fox, conf, 1234
        // specials: conf1234 → dedup: conf1234
        // 合并 LinkedHashSet: fox, conf, 1234, conf1234 = 4
        String[] parts = content.split(" ");
        assertEquals(4, parts.length);
        assertTrue(content.contains("fox"));
        assertTrue(content.contains("conf"));
        assertTrue(content.contains("1234"));
        assertTrue(content.contains("conf1234"));
    }

    @Test
    void indexedContentNullReturnsEmpty() {
        assertEquals("", buildIndexedContent(null));
    }

    @Test
    void queryTokensEmptyFlag() {
        TextAnalyzer.QueryTokens tokens = new TextAnalyzer.QueryTokens(List.of(), List.of());
        assertTrue(tokens.isEmpty());
    }

    @Test
    void queryTokensNotEmptyWithOnlySpecials() {
        // 仅含特殊 token 时 isEmpty=false（Bm25Store.search 依赖该判断决定是否短路）
        TextAnalyzer.QueryTokens tokens = new TextAnalyzer.QueryTokens(List.of(), List.of("conf1234"));
        assertTrue(!tokens.isEmpty());
    }
}