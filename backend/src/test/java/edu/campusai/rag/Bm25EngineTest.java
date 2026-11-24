package edu.campusai.rag;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Bm25EngineTest {
    private final Bm25Engine engine = new Bm25Engine(0.5);

    @Test
    void calculatesBm25AgainstAnIndependentNumericExample() {
        // N=3, df(alpha)=2, average token length=2, d1: tf=2, length=3.
        var hits = engine.search(List.of(
                new Bm25Engine.Document("d1", "alpha alpha beta"),
                new Bm25Engine.Document("d2", "alpha beta"),
                new Bm25Engine.Document("d3", "gamma")), "alpha", 3);
        double expected = Math.log(1 + 1.5 / 2.5) * 4.4 / (2 + 1.2 * (0.25 + 0.75 * 1.5));
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).id()).isEqualTo("d1");
        assertThat(hits.get(0).score()).isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(hits.get(0).coverage()).isEqualTo(1.0);
    }

    @Test
    void retrievesChineseCourseTermsAndKeepsTermFrequency() {
        assertThat(engine.tokenize("软件测试 软件测试")).contains("软件", "测试");
        assertThat(engine.tokenize("软件测试 软件测试").stream().filter("测试"::equals).count()).isEqualTo(2);
        var hits = engine.search(List.of(
                new Bm25Engine.Document("test", "软件测试通过设计测试用例发现程序缺陷。"),
                new Bm25Engine.Document("database", "数据库事务必须满足原子性和一致性。")),
                "什么是软件测试？", 3);
        assertThat(hits).extracting(Bm25Engine.Hit::id).containsExactly("test");
    }

    @Test
    void repeatedQueryWordsDoNotInflateScoresOrCoverage() {
        var documents = List.of(new Bm25Engine.Document("d", "alpha beta beta gamma"));
        assertThat(engine.search(documents, "alpha alpha beta beta", 1))
                .isEqualTo(engine.search(documents, "alpha beta", 1));
    }

    @Test
    void normalizesLengthAndSaturatesTermFrequency() {
        var hits = engine.search(List.of(
                new Bm25Engine.Document("short", "alpha beta"),
                new Bm25Engine.Document("long", "alpha beta gamma delta epsilon zeta theta iota")),
                "alpha", 2);
        assertThat(hits).extracting(Bm25Engine.Hit::id).containsExactly("short", "long");

        // At b=0.75 the three-times-longer all-alpha document still gains less
        // than three times the score, exhibiting tf saturation and length norm.
        var saturation = engine.search(List.of(
                new Bm25Engine.Document("one", "alpha"),
                new Bm25Engine.Document("three", "alpha alpha alpha")), "alpha", 2);
        double first = saturation.stream().filter(h -> h.id().equals("one")).findFirst().orElseThrow().score();
        double triple = saturation.stream().filter(h -> h.id().equals("three")).findFirst().orElseThrow().score();
        assertThat(triple).isGreaterThan(first).isLessThan(3 * first);
    }

    @Test
    void gatesByDistinctQueryCoverageAndOrdersTiesById() {
        var documents = List.of(
                new Bm25Engine.Document("z", "alpha beta gamma"),
                new Bm25Engine.Document("a", "alpha beta gamma"),
                new Bm25Engine.Document("partial", "alpha delta epsilon"));
        var hits = engine.search(documents, "alpha beta gamma theta", 3);
        assertThat(hits).extracting(Bm25Engine.Hit::id).containsExactly("a", "z");
        assertThat(hits).allSatisfy(hit -> assertThat(hit.coverage()).isEqualTo(0.75));
        assertThat(engine.search(documents, "alpha beta gamma theta", 1)).hasSize(1);
    }

    @Test
    void returnsNoEvidenceForBlankNonlexicalUnknownAndOnlyStopWords() {
        var documents = List.of(new Bm25Engine.Document("d", "数据库 软件测试"));
        for (String query : List.of("", "  ", "？！！", "什么 如何 为什么", "volcano eruption")) {
            assertThat(engine.search(documents, query, 3)).as(query).isEmpty();
        }
        assertThat(engine.search(List.of(), "软件测试", 3)).isEmpty();
        assertThat(engine.search(List.of(new Bm25Engine.Document("d", "，，什么")), "软件测试", 3)).isEmpty();
    }

    @Test
    void normalizesLatinCaseAndFullWidthCharacters() {
        assertThat(engine.tokenize("ＡＬＰＨＡ Beta")).isEqualTo(engine.tokenize("alpha beta"));
    }

    @Test
    void rejectsInvalidConfigurationDuplicateIdsAndOverlyBroadQuery() {
        assertThatThrownBy(() -> new Bm25Engine(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Bm25Engine(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Bm25Engine(1.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.search(List.of(
                new Bm25Engine.Document("duplicate", "alpha"),
                new Bm25Engine.Document("duplicate", "beta")), "alpha", 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("唯一编号");
        String query = IntStream.range(0, 65).mapToObj(Integer::toString).collect(Collectors.joining(" "));
        assertThatThrownBy(() -> engine.search(List.of(new Bm25Engine.Document("d", "alpha")), query, 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("过多检索词");
        assertThatThrownBy(() -> engine.search(List.of(), "x".repeat(501), 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("500");
    }
}
