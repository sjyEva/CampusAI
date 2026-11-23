package edu.campusai.rag;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Small-corpus BM25 baseline. The caller supplies only one authorized course's
 * published, current-version chunks. A fresh index avoids stale publication or
 * version caches; the authoritative metadata must still be checked by the caller.
 */
@Component
public class Bm25Engine {
    public static final double K1 = 1.2;
    public static final double B = 0.75;
    public static final int MAX_QUERY_TERMS = 64;
    public static final int MAX_QUERY_CHARS = 500;

    // These words carry little topical meaning in course questions. Both query
    // and document lengths use the same analyzer and stop-word policy.
    private static final Set<String> QUESTION_STOP_WORDS = Set.of(
            "什么", "如何", "为什么", "怎样", "哪些", "是否", "怎么", "多少", "什么样",
            "请", "问", "一下", "介绍", "解释", "说明", "有关", "关于", "分别",
            "一个", "一种", "进行", "可以", "能够", "是", "的", "了", "吗", "呢",
            "what", "how", "why", "which", "who", "where", "when", "please", "pleas",
            "explain", "describe", "describ", "does", "do", "did", "could", "would");

    private final double minimumCoverage;

    public Bm25Engine(@Value("${campusai.rag.minimum-coverage:0.5}") double minimumCoverage) {
        if (!Double.isFinite(minimumCoverage) || minimumCoverage <= 0 || minimumCoverage > 1) {
            throw new IllegalArgumentException("检索词覆盖率必须大于 0 且不超过 1");
        }
        this.minimumCoverage = minimumCoverage;
    }

    /**
     * Uses distinct query terms and the Robertson/Lucene non-negative IDF:
     * log(1 + (N - df + 0.5) / (df + 0.5)). Length is the analyzed token count.
     * Coverage is matched distinct query terms / all distinct query terms. It is
     * a conservative lexical gate, not a probability or proof of answer support.
     */
    public List<Hit> search(List<Document> documents, String query, int limit) {
        if (documents == null) {
            throw new IllegalArgumentException("检索资料不能为空");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("检索结果数量必须在 1 到 100 之间");
        }
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (query.length() > MAX_QUERY_CHARS) {
            throw new IllegalArgumentException("问题不能超过 " + MAX_QUERY_CHARS + " 个字符");
        }
        Set<String> queryTerms = new LinkedHashSet<>(tokenize(query));
        if (queryTerms.size() > MAX_QUERY_TERMS) {
            throw new IllegalArgumentException("问题包含过多检索词，请缩小问题范围");
        }
        if (queryTerms.isEmpty() || documents.isEmpty()) {
            return List.of();
        }

        List<IndexedDocument> index = new ArrayList<>(documents.size());
        Map<String, Integer> documentFrequencies = new HashMap<>();
        Set<String> ids = new HashSet<>();
        long totalLength = 0;
        for (Document document : documents) {
            if (document == null || document.id() == null || document.id().isBlank()
                    || document.text() == null || !ids.add(document.id())) {
                throw new IllegalArgumentException("检索片段必须包含唯一编号和原文");
            }
            List<String> tokens = tokenize(document.text());
            if (tokens.isEmpty()) {
                continue;
            }
            Map<String, Integer> frequencies = new HashMap<>();
            for (String term : tokens) {
                frequencies.merge(term, 1, Integer::sum);
            }
            for (String term : queryTerms) {
                if (frequencies.containsKey(term)) {
                    documentFrequencies.merge(term, 1, Integer::sum);
                }
            }
            index.add(new IndexedDocument(document.id(), frequencies, tokens.size()));
            totalLength += tokens.size();
        }
        if (index.isEmpty()) {
            return List.of();
        }
        double averageLength = (double) totalLength / index.size();
        List<Hit> hits = new ArrayList<>();
        for (IndexedDocument document : index) {
            double score = 0;
            int matched = 0;
            for (String term : queryTerms) {
                int frequency = document.frequencies().getOrDefault(term, 0);
                if (frequency == 0) {
                    continue;
                }
                matched++;
                int df = documentFrequencies.get(term);
                double idf = Math.log(1.0 + (index.size() - df + 0.5) / (df + 0.5));
                double normalization = K1 * (1 - B + B * document.length() / averageLength);
                score += idf * frequency * (K1 + 1) / (frequency + normalization);
            }
            double coverage = (double) matched / queryTerms.size();
            if (score > 0 && coverage >= minimumCoverage) {
                hits.add(new Hit(document.id(), score, coverage));
            }
        }
        return hits.stream()
                .sorted(Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::id))
                .limit(limit)
                .toList();
    }

    /** Returns analyzed terms with repetitions retained for document term frequency. */
    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        try (SmartChineseAnalyzer analyzer = new SmartChineseAnalyzer();
                TokenStream stream = analyzer.tokenStream("content", normalized)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                String value = term.toString();
                if (value.codePoints().anyMatch(Character::isLetterOrDigit)
                        && !QUESTION_STOP_WORDS.contains(value)) {
                    terms.add(value);
                }
            }
            stream.end();
        } catch (IOException exception) {
            throw new IllegalStateException("中文分词失败", exception);
        }
        return List.copyOf(terms);
    }

    public record Document(String id, String text) {
    }

    public record Hit(String id, double score, double coverage) {
    }

    private record IndexedDocument(String id, Map<String, Integer> frequencies, int length) {
    }
}
