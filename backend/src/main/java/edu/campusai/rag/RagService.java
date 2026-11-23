package edu.campusai.rag;

import edu.campusai.auth.DemoAccounts.Account;
import edu.campusai.rag.RagTypes.*;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RagService {
    private final RagAccess access;
    private final RagRepository repository;
    private final Bm25Engine engine;
    private final ModelGateway model;

    public RagService(RagAccess access, RagRepository repository, Bm25Engine engine, ModelGateway model) {
        this.access = access;
        this.repository = repository;
        this.engine = engine;
        this.model = model;
    }

    public SearchResult search(Account account, long courseId, String question, int limit) {
        access.read(account, courseId);
        validateQuestion(question);
        if (limit < 1 || limit > 10) throw new RagException(400, "topK 必须在 1 到 10 之间");
        List<Source> sources = repository.activeSources(courseId);
        Map<String, Source> byId = sources.stream().collect(Collectors.toMap(Source::chunkId, Function.identity()));
        List<SearchHit> hits;
        try {
            hits = engine.search(sources.stream().map(source -> new Bm25Engine.Document(source.chunkId(), source.text())).toList(), question, limit)
                    .stream().map(hit -> new SearchHit(byId.get(hit.id()), hit.score(), hit.coverage())).toList();
        } catch (IllegalArgumentException ex) {
            throw new RagException(400, ex.getMessage());
        }
        access.read(account, courseId);
        if (!repository.allCurrent(courseId, hits.stream().map(SearchHit::source).toList())) {
            return new SearchResult("sources_changed", List.of());
        }
        return new SearchResult(hits.isEmpty() ? "no_evidence" : "ok", hits);
    }

    public Source source(Account account, long courseId, String chunkId) {
        access.read(account, courseId);
        return repository.source(courseId, chunkId)
                .orElseThrow(() -> new RagException(404, "来源不存在、已更新或已下架"));
    }

    public Answer ask(Account account, long courseId, String question) {
        SearchResult search = search(account, courseId, question, 3);
        if (search.hits().isEmpty()) {
            return new Answer(search.status(), search.status().equals("sources_changed")
                    ? "资料状态已变化，请重新提问。" : "未在当前课程已发布资料中检索到有效依据，请补充术语或向教师反馈。",
                    List.of(), List.of(), search.status());
        }
        List<Source> sources = search.hits().stream().map(SearchHit::source).toList();
        ModelGateway.Generation generation = model.generate(question,
                sources.stream().map(source -> new ModelGateway.ModelEvidence(source.chunkId(), source.text())).toList());
        // A provider call can outlive a publication/version or enrollment change.
        // Never return its stale evidence, even in a fallback response.
        access.read(account, courseId);
        if (!repository.allCurrent(courseId, sources)) {
            return new Answer("sources_changed", "资料状态已变化，请重新提问。", List.of(), List.of(), "sources_changed");
        }
        if (!generation.generated()) {
            if (generation.reason().equals("insufficient_evidence")) {
                return new Answer("no_evidence", "检索到的相关原文不足以支持回答，请核对资料或向教师反馈。",
                        List.of(), search.hits(), generation.reason());
            }
            return new Answer("retrieval_only", "本次未生成回答，请查看下方相关原文并核对来源。",
                    List.of(), search.hits(), generation.reason());
        }
        Map<String, Source> byId = sources.stream().collect(Collectors.toMap(Source::chunkId, Function.identity()));
        List<Source> citations = generation.citationIds().stream().map(byId::get).toList();
        return new Answer("generated", generation.answer(), citations, search.hits(), null);
    }

    private static void validateQuestion(String question) {
        if (question == null || question.isBlank() || question.length() > Bm25Engine.MAX_QUERY_CHARS) {
            throw new RagException(400, "问题不能为空且不能超过 500 个字符");
        }
    }
}
