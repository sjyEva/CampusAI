package edu.campusai.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Contrastive identifier fixtures, separate from the frozen 40-question set. */
class RetrievalRegressionTest {
    private final Bm25Engine engine = new Bm25Engine(0.5);

    @Test
    void explicitProtocolMustBePresentEvenWhenOtherWordsMatch() {
        var documents = List.of(
                doc("ordinary", "网络协议通过确认和重传提高传输可靠性。"),
                doc("target", "QUIC 网络协议使用独立流并提供丢包恢复和连接迁移能力。"));
        assertThat(engine.search(documents, "QUIC 网络协议的传输机制", 3))
                .extracting(Bm25Engine.Hit::id).containsExactly("target");
        assertThat(engine.search(List.of(documents.get(0)), "quic网络协议的传输机制", 3)).isEmpty();
    }

    @Test
    void punctuationAndAlphanumericIdentifiersDoNotCollapseToOrdinaryTokens() {
        var coverage = List.of(
                doc("ordinary", "测试覆盖率表示测试用例对程序结构的覆盖程度。"),
                doc("specific", "MC/DC 测试覆盖要求分别验证每个条件对判定结果的独立影响。"));
        assertThat(engine.search(coverage, "MC/DC 测试覆盖", 3))
                .extracting(Bm25Engine.Hit::id).containsExactly("specific");
        assertThat(engine.search(List.of(doc("other", "BM250 使用词频和长度排序。")), "BM25 排序", 3)).isEmpty();
        assertThat(engine.search(List.of(doc("valid", "BM25 使用词频和长度排序。")), "ＢＭ２５ 排序", 3))
                .extracting(Bm25Engine.Hit::id).containsExactly("valid");
    }

    @Test
    void languagesAndLanguageVersionsKeepTheirDistinctions() {
        var documents = List.of(
                doc("c", "C 语言通过指针管理内存。"),
                doc("cpp", "C++ 语言支持类和对象，并提供资源管理机制。"),
                doc("csharp", "C# 语言提供托管对象和垃圾回收。"));
        assertThat(engine.search(documents, "C++ 语言的对象", 3))
                .extracting(Bm25Engine.Hit::id).containsExactly("cpp");
        assertThat(engine.search(documents, "C# 语言的对象", 3))
                .extracting(Bm25Engine.Hit::id).containsExactly("csharp");
        assertThat(engine.search(List.of(doc("wrong", "C++ language supports objects.")), "What is the C language?", 3)).isEmpty();
        assertThat(engine.search(List.of(doc("old", "C++17 标准支持结构化绑定。")), "C++20 标准", 3)).isEmpty();
        assertThat(engine.search(List.of(doc("right", "C++20 标准引入概念和范围库。")), "C++20 标准", 3))
                .extracting(Bm25Engine.Hit::id).containsExactly("right");
    }

    @Test
    void namedSeparatedVersionsMatchTheirCompactSpellingButNotOtherVersions() {
        var documents = List.of(
                doc("old", "Java 17 提供密封类等语言功能。"),
                doc("new", "Java21 提供虚拟线程等运行时功能。"));
        assertThat(engine.search(documents, "Java 21 的功能", 3))
                .extracting(Bm25Engine.Hit::id).containsExactly("new");
        assertThat(engine.search(List.of(documents.get(0)), "Java21 的功能", 3)).isEmpty();
    }

    @Test
    void ordinaryEnglishQuestionsRetainStemMatching() {
        var documents = List.of(doc("tests", "Tests discover program defects."));
        assertThat(engine.search(documents, "How does testing discover defects?", 3))
                .extracting(Bm25Engine.Hit::id).containsExactly("tests");
        assertThat(engine.search(documents, "HOW DOES TESTING DISCOVER DEFECTS?", 3))
                .extracting(Bm25Engine.Hit::id).containsExactly("tests");
        assertThat(engine.requiredIdentifiers("What happens in chapter 3?")).isEmpty();
        assertThat(engine.requiredIdentifiers("What is the TOP 3 result?")).isEmpty();
        assertThat(engine.search(List.of(doc("wrong", "The SQL database supports indexes.")), "How does PostgreSQL support database indexes?", 3)).isEmpty();
    }

    @Test
    void normalChineseQuestionsDoNotAcquireAnExactPhraseRequirement() {
        var documents = List.of(doc("test", "软件测试通过测试用例发现程序缺陷。"));
        assertThat(engine.search(documents, "如何发现软件程序的缺陷", 3))
                .extracting(Bm25Engine.Hit::id).containsExactly("test");
        assertThat(engine.requiredIdentifiers("如何发现软件程序的缺陷")).isEmpty();
    }

    @Test
    void hyphenatedEnglishSuffixStillNamesItsTechnicalComponent() {
        assertThat(engine.search(List.of(doc("right", "BM25-based ranking uses token frequencies.")), "How does BM25 ranking work?", 3))
                .extracting(Bm25Engine.Hit::id).containsExactly("right");
        assertThat(engine.search(List.of(doc("wrong", "SHA checksum digest")), "SHA-256 checksum", 3)).isEmpty();
    }

    @Test
    void translatedAliasWithoutTheRequestedAbbreviationIsConservativelyRejected() {
        // Deliberately documents the precision/recall tradeoff; there is no
        // unreviewed synonym dictionary equating translations with identifiers.
        assertThat(engine.search(List.of(doc("translation", "超文本传输协议提供请求与响应机制。")), "HTTP 请求响应机制", 3)).isEmpty();
    }

    @Test
    void identifierPresenceIsNotTreatedAsProofOfAnswerSupport() {
        // A disclaimer still mentions the identifier. This filter is not a
        // semantic entailment/negation classifier and must not claim otherwise.
        assertThat(engine.search(List.of(doc("disclaimer", "本资料不介绍 TLS 1.3 握手细节。")), "TLS 1.3 握手细节", 3))
                .extracting(Bm25Engine.Hit::id).containsExactly("disclaimer");
    }

    private static Bm25Engine.Document doc(String id, String text) {
        return new Bm25Engine.Document(id, text);
    }
}
