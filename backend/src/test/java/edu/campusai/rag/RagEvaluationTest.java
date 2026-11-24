package edu.campusai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Fixed developer-authored diagnostic set, not an independently held-out benchmark. */
class RagEvaluationTest {
    private static final String QUESTIONS_SHA256 = "db2a6a4c442d2098683d68290e97483720d581ac361c2aabe04665ee5863e616";

    @Test
    void recordsFrozenFortyQuestionEvaluationUsingTheProductionParserAndRanker() throws Exception {
        Path backend = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        Path ragDocs = backend.resolve("../docs/rag").normalize();
        if (!Files.exists(ragDocs.resolve("evaluation/questions.json"))) {
            backend = backend.resolve("backend");
            ragDocs = backend.resolve("../docs/rag").normalize();
        }
        byte[] frozenQuestions = Files.readAllBytes(ragDocs.resolve("evaluation/questions.json"));
        assertThat(sha256(frozenQuestions)).as("Frozen v1 question set must not be silently edited")
                .isEqualTo(QUESTIONS_SHA256);
        ObjectMapper json = new ObjectMapper();
        JsonNode dataset = json.readTree(frozenQuestions);
        DocumentParser parser = new DocumentParser();
        Bm25Engine engine = new Bm25Engine(0.5);
        Map<Integer, List<SourceChunk>> sources = new LinkedHashMap<>();
        Map<Integer, List<Bm25Engine.Document>> documents = new LinkedHashMap<>();
        for (int courseId : List.of(1, 2)) {
            String filename = "course-" + courseId + "-evaluation.txt";
            byte[] content = Files.readAllBytes(ragDocs.resolve("samples/" + filename));
            assertThat(sha256(content)).as("Frozen corpus " + filename)
                    .isEqualTo(dataset.path("files").path(filename).asText());
            List<SourceChunk> chunks = new ArrayList<>();
            for (DocumentParser.ParsedChunk chunk : parser.parse(filename, content)) {
                String id = "C" + courseId + "-P" + chunk.paragraphStart() + "-" + chunk.paragraphEnd()
                        + "-" + (chunks.size() + 1);
                chunks.add(new SourceChunk(id, filename, chunk));
            }
            sources.put(courseId, chunks);
            documents.put(courseId, chunks.stream().map(chunk ->
                    new Bm25Engine.Document(chunk.id(), chunk.parsed().text())).toList());
        }

        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Totals> totals = new LinkedHashMap<>();
        totals.put("development", new Totals());
        totals.put("acceptance", new Totals());
        long started = System.nanoTime();
        for (JsonNode question : dataset.path("questions")) {
            String split = question.path("split").asText();
            Totals summary = totals.get(split);
            assertThat(summary).as("Known dataset split").isNotNull();
            int courseId = question.path("courseId").asInt();
            boolean supported = question.path("supported").asBoolean();
            String quote = question.path("expectedQuote").asText("");
            int paragraph = question.path("expectedParagraph").asInt();
            List<String> goldChunkIds = sources.get(courseId).stream()
                    .filter(source -> supported && source.filename().equals(question.path("sourceFile").asText())
                            && source.parsed().text().contains(quote)
                            && source.parsed().paragraphStart() <= paragraph
                            && source.parsed().paragraphEnd() >= paragraph)
                    .map(SourceChunk::id).toList();
            if (supported) {
                assertThat(goldChunkIds).as("Gold quote and paragraph exist for " + question.path("id").asText())
                        .isNotEmpty();
            }
            long queryStarted = System.nanoTime();
            List<Bm25Engine.Hit> hits = engine.search(documents.get(courseId), question.path("question").asText(), 3);
            double elapsedMs = (System.nanoTime() - queryStarted) / 1_000_000.0;
            boolean hitAt3 = supported && hits.stream().anyMatch(hit -> goldChunkIds.contains(hit.id()));
            if (supported) {
                summary.supported++;
                if (hitAt3) {
                    summary.hits++;
                }
            } else {
                summary.unsupported++;
                if (hits.isEmpty()) {
                    summary.correctEmpty++;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", question.path("id").asText());
            row.put("split", split);
            row.put("courseId", courseId);
            row.put("question", question.path("question").asText());
            row.put("supported", supported);
            row.put("expectedParagraph", supported ? paragraph : null);
            row.put("expectedQuote", supported ? quote : null);
            row.put("goldChunkIds", goldChunkIds);
            row.put("top3", hits);
            row.put("hitAt3", supported ? hitAt3 : null);
            row.put("emptyOnUnsupported", supported ? null : hits.isEmpty());
            row.put("elapsedMs", elapsedMs);
            results.add(row);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("version", dataset.path("version").asText());
        report.put("executedAt", Instant.now().toString());
        report.put("questionsSha256", QUESTIONS_SHA256);
        report.put("corpusSha256", dataset.path("files"));
        report.put("provenance", dataset.path("provenance").asText());
        report.put("parameters", Map.of("k1", Bm25Engine.K1, "b", Bm25Engine.B, "minimumCoverage", 0.5,
                "maxChunkChars", DocumentParser.MAX_CHUNK_CHARS, "topK", 3, "analyzer", "SmartChineseAnalyzer 9.12.3"));
        report.put("environment", Map.of("java", System.getProperty("java.version"), "os", System.getProperty("os.name"),
                "arch", System.getProperty("os.arch")));
        report.put("chunkCounts", Map.of("course1", sources.get(1).size(), "course2", sources.get(2).size()));
        report.put("totalRetrievalElapsedMs", (System.nanoTime() - started) / 1_000_000.0);
        Map<String, Object> splitSummaries = new LinkedHashMap<>();
        for (Map.Entry<String, Totals> entry : totals.entrySet()) {
            Totals count = entry.getValue();
            splitSummaries.put(entry.getKey(), Map.of("supportedQuestions", count.supported, "hitAt3Count", count.hits,
                    "hitAt3", count.supported == 0 ? 0 : (double) count.hits / count.supported,
                    "unsupportedQuestions", count.unsupported, "unsupportedEmptyCount", count.correctEmpty,
                    "unsupportedFalsePositiveCount", count.unsupported - count.correctEmpty));
        }
        report.put("summary", splitSummaries);
        report.put("questions", results);
        Path output = backend.resolve("target");
        Files.createDirectories(output);
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("rag-evaluation-results.json").toFile(), report);
        Files.writeString(output.resolve("rag-evaluation-results.md"), markdown(report, totals, results), StandardCharsets.UTF_8);

        // Persist every result before enforcing the supported-query target. The
        // unsupported-query diagnostic deliberately records failures honestly.
        assertThat(results).hasSize(40);
        for (Map.Entry<String, Totals> entry : totals.entrySet()) {
            assertThat(entry.getValue().supported).as(entry.getKey() + " supported denominator").isEqualTo(15);
            assertThat(entry.getValue().unsupported).as(entry.getKey() + " unsupported denominator").isEqualTo(5);
            assertThat(entry.getValue().hits).as(entry.getKey() + " Hit@3 >= 12/15").isGreaterThanOrEqualTo(12);
        }
    }

    private static String markdown(Map<String, Object> report, Map<String, Totals> totals,
            List<Map<String, Object>> results) {
        StringBuilder text = new StringBuilder("# CampusAI 开发者固定样例检索评估\n\n");
        text.append("运行时间：").append(report.get("executedAt")).append("。\n\n")
                .append(report.get("provenance")).append("\n\n")
                .append("本次只评估真实解析及 BM25 检索，不代表生成答案正确率或真实模型供应商可用性。")
                .append("命中要求前三个片段包含标注原文且段落范围覆盖标注位置。无依据题单独统计，未计入 Hit@3 分母。\n\n")
                .append("| 集合 | 有依据题 Hit@3 | 无依据题返回空结果 | 无依据题误召回 |\n|---|---|---|---|\n");
        for (Map.Entry<String, Totals> entry : totals.entrySet()) {
            Totals count = entry.getValue();
            text.append("| ").append(entry.getKey()).append(" | ").append(count.hits).append("/")
                    .append(count.supported).append(" | ").append(count.correctEmpty).append("/")
                    .append(count.unsupported).append(" | ").append(count.unsupported - count.correctEmpty).append(" |\n");
        }
        text.append("\n参数：k1=1.2，b=0.75，最低检索词覆盖率=0.5，片段上限=700 字符，TopK=3。\n\n")
                .append("片段数量：").append(report.get("chunkCounts")).append("。语料规模较小且由开发者自编，结果只证明本固定样例的行为，不外推到真实课程效果。\n\n")
                .append("| 编号 | 集合 | 问题 | 有依据 | 命中 / 正确空结果 |\n|---|---|---|---|---|\n");
        for (Map<String, Object> row : results) {
            boolean supported = (boolean) row.get("supported");
            text.append("| ").append(row.get("id")).append(" | ").append(row.get("split")).append(" | ")
                    .append(row.get("question")).append(" | ").append(supported ? "是" : "否").append(" | ")
                    .append(supported ? row.get("hitAt3") : row.get("emptyOnUnsupported")).append(" |\n");
        }
        text.append("\n逐题原文、来源片段编号、得分、检索词覆盖率和耗时见同目录 JSON。词项覆盖只是词面启发式；相关术语出现并不保证资料回答了问题。\n");
        return text.toString();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record SourceChunk(String id, String filename, DocumentParser.ParsedChunk parsed) {
    }

    private static class Totals {
        private int supported;
        private int hits;
        private int unsupported;
        private int correctEmpty;
    }
}
