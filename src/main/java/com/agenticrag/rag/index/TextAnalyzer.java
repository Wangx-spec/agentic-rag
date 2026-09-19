package com.agenticrag.rag.index;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BM25 通道统一文本分析器（M6 Phase 2 查询构造优化）。
 * <p>
 * 核心原则：入库侧 buildIndexedContent 与查询侧 buildQueryTokens 走同一分词/归一管线，
 * 保证 FTS5 MATCH 两侧 token 严格对称（词干归一若只用在单侧会造成永久匹配缺口）。
 * - 英文 token：停用词过滤（stopwords-en.txt）+ Porter 词干归一
 * - 中文：保持二字 bigram（历史行为不变）
 * - 特殊 token：JIRA 编号（CONF-1234）/ 日期（2024-01-15）/ 长编号（ABC123DEF）
 *   生成紧凑形态（conf1234）。unicode61 分词器按符号切开，紧凑形态让高区分度
 *   token 整体可命中，并借 bm25() 对稀有 token 的高 idf 自然加权。
 */
@Slf4j
public final class TextAnalyzer {

    private static final Pattern JIRA_ID = Pattern.compile("\\b[A-Z]+-\\d+\\b");
    private static final Pattern DATE_LIKE = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
    private static final Pattern LONG_CODE = Pattern.compile("\\b[A-Z0-9]{6,}\\b");

    private static final Set<String> STOP_WORDS = loadStopWords();

    private TextAnalyzer() {
    }

    // ==================== 对外 API ====================

    /** 入库侧：chunk 原文 → indexed_content 文本 */
    public static String buildIndexedContent(String text) {
        QueryTokens tokens = buildQueryTokens(text);
        if (tokens.isEmpty()) {
            return "";
        }
        LinkedHashSet<String> all = new LinkedHashSet<>(tokens.normalTokens());
        all.addAll(tokens.specialTokens());
        return String.join(" ", all);
    }

    /** 查询侧：query → token 集（normal：停用词过滤+词干；special：紧凑形态） */
    public static QueryTokens buildQueryTokens(String text) {
        if (text == null || text.isBlank()) {
            return new QueryTokens(List.of(), List.of());
        }
        List<String> normal = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        StringBuilder asciiToken = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isAsciiLetterOrDigit(c)) {
                asciiToken.append(c);
                continue;
            }
            flushToken(sb, asciiToken);
            if (isAscii(c)) {
                continue; // ASCII 标点/空白作为分隔符，避免 MATCH 把 - : 等解释成操作符
            }
            if (i + 1 < text.length() && !isAscii(text.charAt(i + 1))) {
                sb.append(c).append(text.charAt(i + 1)).append(' ');
            } else {
                sb.append(c).append(' ');
            }
        }
        flushToken(sb, asciiToken);
        for (String token : sb.toString().trim().split("\\s+")) {
            String normalized = normalizeToken(token);
            if (normalized != null) {
                normal.add(normalized);
            }
        }
        LinkedHashSet<String> special = new LinkedHashSet<>();
        for (Pattern pattern : List.of(JIRA_ID, DATE_LIKE, LONG_CODE)) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                special.add(matcher.group().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
            }
        }
        return new QueryTokens(List.copyOf(normal), List.copyOf(special));
    }

    /** AND 主查询：全部 token（含特殊 token）同时命中 */
    public static String buildAndQuery(QueryTokens tokens) {
        List<String> all = new ArrayList<>(tokens.normalTokens());
        all.addAll(tokens.specialTokens());
        return joinQuoted(all, " ");
    }

    /**
     * 降级/加权查询：特殊 token 保持 AND 强约束，普通 token 放宽为 OR。
     * FTS5 无 token 级 boost 语法，文档 2.3 的"单独加权"工程化为：
     * 特殊 token 永不放宽 + bm25() 对稀有 token 的高 idf 天然放大权重。
     */
    public static String buildOrQuery(QueryTokens tokens) {
        if (tokens.specialTokens().isEmpty()) {
            return joinQuoted(tokens.normalTokens(), " OR ");
        }
        StringBuilder sb = new StringBuilder(joinQuoted(tokens.specialTokens(), " AND "));
        if (!tokens.normalTokens().isEmpty()) {
            sb.append(" AND (").append(joinQuoted(tokens.normalTokens(), " OR ")).append(')');
        }
        return sb.toString();
    }

    public record QueryTokens(List<String> normalTokens, List<String> specialTokens) {
        public boolean isEmpty() {
            return normalTokens.isEmpty() && specialTokens.isEmpty();
        }
    }

    // ==================== 内部实现 ====================

    private static String normalizeToken(String token) {
        if (token.isBlank()) {
            return null;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        if (STOP_WORDS.contains(lower)) {
            return null;
        }
        return isPureAsciiWord(lower) ? PorterStemmer.stem(lower) : lower;
    }

    private static boolean isPureAsciiWord(String token) {
        if (token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < 'a' || c > 'z') {
                return false;
            }
        }
        return true;
    }

    private static String joinQuoted(List<String> tokens, String delimiter) {
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            if (!sb.isEmpty()) {
                sb.append(delimiter);
            }
            sb.append('"').append(token.replace("\"", "\"\"")).append('"');
        }
        return sb.toString();
    }

    private static void flushToken(StringBuilder sb, StringBuilder asciiToken) {
        if (asciiToken.isEmpty()) {
            return;
        }
        sb.append(asciiToken).append(' ');
        asciiToken.setLength(0);
    }

    private static boolean isAsciiLetterOrDigit(char c) {
        return c < 128 && Character.isLetterOrDigit(c);
    }

    private static boolean isAscii(char c) {
        return c < 128;
    }

    private static Set<String> loadStopWords() {
        try (InputStream in = TextAnalyzer.class.getClassLoader().getResourceAsStream("stopwords-en.txt")) {
            if (in == null) {
                log.warn("stopwords-en.txt 未找到，BM25 停用词过滤降级为空表");
                return Set.of();
            }
            Set<String> words = new LinkedHashSet<>();
            try (Scanner scanner = new Scanner(in, StandardCharsets.UTF_8)) {
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine().strip().toLowerCase(Locale.ROOT);
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        words.add(line);
                    }
                }
            }
            return Set.copyOf(words);
        } catch (Exception e) {
            log.warn("停用词表加载失败，BM25 停用词过滤降级为空表: {}", e.getMessage());
            return Set.of();
        }
    }

    /**
     * 经典 Porter Stemmer（1980）紧凑实现，无外部依赖。
     * 词干的语言学精度并不关键——关键是入库/查询两侧使用同一实现保证对称。
     */
    static final class PorterStemmer {

        private PorterStemmer() {
        }

        private static final String[][] STEP2_RULES = {
                {"ization", "ize"}, {"ational", "ate"}, {"fulness", "ful"}, {"ousness", "ous"},
                {"iveness", "ive"}, {"tional", "tion"}, {"biliti", "ble"}, {"entli", "ent"},
                {"ousli", "ous"}, {"ation", "ate"}, {"alism", "al"}, {"aliti", "al"},
                {"iviti", "ive"}, {"abli", "able"}, {"alli", "al"}, {"izer", "ize"},
                {"anci", "ance"}, {"enci", "ence"}, {"ator", "ate"}, {"logi", "log"}, {"eli", "e"},
        };

        private static final String[][] STEP3_RULES = {
                {"icate", "ic"}, {"ative", ""}, {"alize", ""}, {"iciti", "ic"},
                {"ical", "ic"}, {"ful", ""}, {"ness", ""},
        };

        static String stem(String word) {
            String w = word.toLowerCase(Locale.ROOT);
            if (w.length() <= 2) {
                return w;
            }
            w = step1a(w);
            w = step1b(w);
            w = step1c(w);
            w = applyRules(w, STEP2_RULES, 1);
            w = applyRules(w, STEP3_RULES, 1);
            w = step4(w);
            return step5(w);
        }

        private static String step1a(String w) {
            if (w.endsWith("sses")) {
                return w.substring(0, w.length() - 2);
            }
            if (w.endsWith("ies")) {
                return w.substring(0, w.length() - 2);
            }
            if (w.endsWith("ss")) {
                return w;
            }
            if (w.endsWith("s")) {
                return w.substring(0, w.length() - 1);
            }
            return w;
        }

        private static String step1b(String w) {
            if (w.endsWith("eed")) {
                String stem = w.substring(0, w.length() - 3);
                return measure(stem) > 0 ? stem + "ee" : w;
            }
            boolean removed = false;
            if (w.endsWith("ed") && containsVowel(w, w.length() - 2)) {
                w = w.substring(0, w.length() - 2);
                removed = true;
            } else if (w.endsWith("ing") && containsVowel(w, w.length() - 3)) {
                w = w.substring(0, w.length() - 3);
                removed = true;
            }
            if (!removed) {
                return w;
            }
            if (w.endsWith("at") || w.endsWith("bl") || w.endsWith("iz")) {
                return w + "e";
            }
            if (endsWithDoubleConsonant(w) && !(w.endsWith("l") || w.endsWith("s") || w.endsWith("z"))) {
                return w.substring(0, w.length() - 1);
            }
            if (measure(w) == 1 && cvc(w)) {
                return w + "e";
            }
            return w;
        }

        private static String step1c(String w) {
            if (w.endsWith("y") && containsVowel(w, w.length() - 1)) {
                return w.substring(0, w.length() - 1) + "i";
            }
            return w;
        }

        /** 按最长匹配应用规则表；命中后缀即结束本步（即使 measure 不足也不再尝试其他后缀） */
        private static String applyRules(String w, String[][] rules, int minMeasure) {
            for (String[] rule : rules) {
                if (w.endsWith(rule[0])) {
                    String stem = w.substring(0, w.length() - rule[0].length());
                    return measure(stem) >= minMeasure ? stem + rule[1] : w;
                }
            }
            return w;
        }

        private static String step4(String w) {
            String[] suffixes = {"ement", "ance", "ence", "able", "ible", "ment", "ant",
                    "ent", "ism", "ate", "iti", "ous", "ive", "ize", "ion", "al", "er", "ic", "ou"};
            for (String suffix : suffixes) {
                if (w.endsWith(suffix)) {
                    String stem = w.substring(0, w.length() - suffix.length());
                    if (measure(stem) <= 1) {
                        return w;
                    }
                    if (suffix.equals("ion") && !stem.endsWith("s") && !stem.endsWith("t")) {
                        return w;
                    }
                    return stem;
                }
            }
            return w;
        }

        private static String step5(String w) {
            if (w.endsWith("e")) {
                String stem = w.substring(0, w.length() - 1);
                int m = measure(stem);
                if (m > 1 || (m == 1 && !cvc(stem))) {
                    return stem;
                }
            }
            if (measure(w) > 1 && endsWithDoubleConsonant(w) && w.endsWith("l")) {
                return w.substring(0, w.length() - 1);
            }
            return w;
        }

        private static boolean isConsonant(String w, int i) {
            char c = w.charAt(i);
            return switch (c) {
                case 'a', 'e', 'i', 'o', 'u' -> false;
                case 'y' -> i == 0 || !isConsonant(w, i - 1);
                default -> true;
            };
        }

        /** 音节数 m：[C](VC)^m[V] */
        private static int measure(String w) {
            int m = 0;
            int i = 0;
            int n = w.length();
            while (i < n && isConsonant(w, i)) {
                i++;
            }
            while (true) {
                while (i < n && !isConsonant(w, i)) {
                    i++;
                }
                if (i >= n) {
                    return m;
                }
                m++;
                while (i < n && isConsonant(w, i)) {
                    i++;
                }
                if (i >= n) {
                    return m;
                }
            }
        }

        private static boolean containsVowel(String w, int upTo) {
            for (int i = 0; i < upTo && i < w.length(); i++) {
                if (!isConsonant(w, i)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean endsWithDoubleConsonant(String w) {
            int n = w.length();
            return n >= 2 && w.charAt(n - 1) == w.charAt(n - 2) && isConsonant(w, n - 1);
        }

        /** *o 条件：末三字符为 辅-元-辅 且末字符非 w/x/y */
        private static boolean cvc(String w) {
            int n = w.length();
            if (n < 3) {
                return false;
            }
            char last = w.charAt(n - 1);
            if (last == 'w' || last == 'x' || last == 'y') {
                return false;
            }
            return isConsonant(w, n - 3) && !isConsonant(w, n - 2) && isConsonant(w, n - 1);
        }
    }
}
