package edu.campusai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ModelGatewayTest {
    private static final String A = "chunk-a";
    private static final String B = "chunk-b";
    private static final String ORIGINAL = "残差连接通过恒等映射为梯度传播提供直接路径，帮助训练深层网络。";
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedAuthorization = new AtomicReference<>();
    private HttpServer server;
    private ExecutorService executor;
    private String baseUrl;

    @BeforeEach
    void startProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopProvider() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void producesServerNumberedCitationsAndSendsOnlySuppliedEvidence() throws Exception {
        reply(200, completion(List.of(
                claim("提供直接的传播路径。", B, "为梯度传播提供直接路径"),
                claim("有助于训练深层网络。", A, "帮助训练深层网络"),
                claim("恒等映射连接层间表示。", B, "通过恒等映射"))));
        ModelGateway.Generation result = gateway(2).generate("残差连接有什么作用？", List.of(
                new ModelGateway.ModelEvidence(A, ORIGINAL), new ModelGateway.ModelEvidence(B, ORIGINAL)));
        assertTrue(result.generated());
        assertEquals("generated", result.reason());
        assertEquals(List.of(B, A), result.citationIds());
        assertEquals("提供直接的传播路径。 [1]\n\n有助于训练深层网络。 [2]\n\n恒等映射连接层间表示。 [1]", result.answer());
        assertEquals(3, result.claims().size());
        assertEquals(List.of(B, A, B), result.claims().stream().map(ModelGateway.GroundedClaim::chunkId).toList());
        assertEquals("为梯度传播提供直接路径", result.claims().get(0).quote());
        assertEquals("提供直接的传播路径。", result.claims().get(0).text());
        JsonNode request = json.readTree(capturedBody.get());
        assertEquals("test-model", request.path("model").asText());
        assertFalse(request.path("stream").asBoolean());
        assertFalse(request.has("response_format"));
        assertFalse(request.has("thinking"));
        assertEquals("Bearer test-key", capturedAuthorization.get());
        JsonNode input = json.readTree(request.path("messages").get(1).path("content").asText());
        assertEquals(2, input.path("evidence").size());
        assertEquals(ORIGINAL, input.path("evidence").get(0).path("text").asText());
    }

    @Test
    void enablesJsonOutputAndDisablesThinkingIndependentlyAtTheRequestTopLevel() throws Exception {
        reply(200, completion(List.of(claim("有助于训练深层网络。", A, "帮助训练深层网络"))));
        for (boolean jsonMode : List.of(false, true)) {
            for (boolean disableThinking : List.of(false, true)) {
                ModelGateway gateway = new ModelGateway(baseUrl, "test-key", "deepseek-flash", 2,
                        jsonMode, disableThinking, json);
                assertTrue(gateway.generate("残差连接的作用？", evidence()).generated());
                JsonNode request = json.readTree(capturedBody.get());
                assertEquals("deepseek-flash", request.path("model").asText());
                assertFalse(request.has("extra_body"), "The raw HTTP API needs options at the top level");
                if (jsonMode) {
                    assertEquals(json.valueToTree(Map.of("type", "json_object")), request.path("response_format"));
                    assertTrue(request.path("messages").get(0).path("content").asText().contains("JSON"));
                } else {
                    assertFalse(request.has("response_format"));
                }
                if (disableThinking) {
                    assertEquals(json.valueToTree(Map.of("type", "disabled")), request.path("thinking"));
                } else {
                    assertFalse(request.has("thinking"));
                }
            }
        }
        assertEquals(4, requests.get());
    }

    @Test
    void jsonModeStillRejectsBlankContentWithAStopFinishReason() throws Exception {
        reply(200, envelope("   ", "stop"));
        ModelGateway gateway = new ModelGateway(baseUrl, "test-key", "deepseek-flash", 2, true, true, json);
        assertFallback(gateway.generate("残差连接的作用？", evidence()), "invalid_response");
    }

    @Test
    void emptyEvidenceInvalidInputsAndUnconfiguredProviderNeverSendARequest() throws Exception {
        reply(500, "must not be called");
        assertFallback(gateway(2).generate("问题", List.of()), "no_evidence");
        assertFallback(gateway(2).generate("  ", evidence()), "invalid_request");
        assertFallback(gateway(2).generate("问".repeat(2001), evidence()), "invalid_request");
        assertFallback(gateway(2).generate("问题", List.of(
                new ModelGateway.ModelEvidence(A, ORIGINAL), new ModelGateway.ModelEvidence(A, ORIGINAL))), "invalid_request");
        assertFallback(new ModelGateway("", "", "", 2, json).generate("问题", evidence()), "unconfigured");
        assertEquals(0, requests.get());
    }

    @Test
    void rejectsForeignCitationEvenWhenTheQuoteIsReal() throws Exception {
        reply(200, completion(List.of(claim("声称有依据", "another-course-chunk", "通过恒等映射"))));
        assertFallback(ask(), "invalid_citation");
    }

    @Test
    void rejectsInventedOrTooShortQuote() throws Exception {
        reply(200, completion(List.of(claim("声称有依据", A, "不存在的支持语句"))));
        assertFallback(ask(), "invalid_citation");
        replaceReply(200, completion(List.of(claim("声称有依据", A, "残差"))));
        assertFallback(ask(), "invalid_citation");
    }

    @Test
    void exposesExactUnicodeQuoteOffsetsAndUsesFirstOccurrenceWithoutRewritingSource() throws Exception {
        String source = "😀引言：残差连接提供路径。后文再次说明：残差连接提供路径。";
        String quote = "残差连接提供路径";
        reply(200, completion(List.of(claim("  连接提供路径。  ", A, quote))));
        ModelGateway.Generation result = gateway(2).generate("连接的作用？",
                List.of(new ModelGateway.ModelEvidence(A, source)));
        assertTrue(result.generated());
        ModelGateway.GroundedClaim support = result.claims().get(0);
        assertEquals("连接提供路径。", support.text());
        // One emoji occupies two UTF-16 units, then three ordinary Chinese/punctuation characters.
        assertEquals(5, support.quoteStart());
        assertEquals(13, support.quoteEnd());
        assertEquals(quote, source.substring(support.quoteStart(), support.quoteEnd()));
        assertTrue(source.substring(support.quoteEnd()).contains(quote), "Repeated text must not move the offset to the last occurrence");
        assertThrows(UnsupportedOperationException.class, () -> result.claims().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.citationIds().clear());
    }

    @Test
    void preservesAWholeSupplementaryCharacterInsideTheQuote() throws Exception {
        String source = "位置：😀甲乙丙。";
        String quote = "😀甲乙丙";
        reply(200, completion(List.of(claim("引用包括完整的补充平面字符。", A, quote))));
        ModelGateway.Generation result = gateway(2).generate("内容？",
                List.of(new ModelGateway.ModelEvidence(A, source)));
        assertTrue(result.generated());
        ModelGateway.GroundedClaim support = result.claims().get(0);
        assertEquals(3, support.quoteStart());
        assertEquals(8, support.quoteEnd());
        assertEquals(quote, source.substring(support.quoteStart(), support.quoteEnd()));
    }

    @Test
    void rejectsQuotesThatOnlyReachFourUtf16UnitsOrSplitASurrogatePair() throws Exception {
        String source = "😀😁甲乙丙丁戊";
        reply(200, completion(List.of(claim("两个表情不算四个完整字符。", A, "😀😁"))));
        assertFallback(gateway(2).generate("内容？", List.of(new ModelGateway.ModelEvidence(A, source))), "invalid_citation");

        // A malformed escaped low surrogate can otherwise match the second half of 😁.
        String halfStart = "{\"claims\":[{\"text\":\"不能从半个字符开始定位。\",\"chunkId\":\"chunk-a\",\"quote\":\""
                + '\\' + "uDE01甲乙丙丁\"}]}";
        replaceReply(200, envelope(halfStart, "stop"));
        assertFallback(gateway(2).generate("内容？", List.of(new ModelGateway.ModelEvidence(A, source))), "invalid_citation");

        String suffixSource = "甲乙丙丁😀";
        String halfEnd = "{\"claims\":[{\"text\":\"不能在半个字符处结束定位。\",\"chunkId\":\"chunk-a\",\"quote\":\"甲乙丙丁"
                + '\\' + "uD83D\"}]}";
        replaceReply(200, envelope(halfEnd, "stop"));
        assertFallback(gateway(2).generate("内容？", List.of(new ModelGateway.ModelEvidence(A, suffixSource))), "invalid_citation");
    }

    @Test
    void neverReturnsPartialValidatedQuotesWhenALaterClaimUsesAForeignSource() throws Exception {
        reply(200, completion(List.of(
                claim("第一条拥有真实引文。", A, "通过恒等映射"),
                claim("第二条试图使用另一课程资料。", "foreign-id", "通过恒等映射"))));
        ModelGateway.Generation result = ask();
        assertFallback(result, "invalid_citation");
        assertTrue(result.claims().isEmpty());
    }

    @Test
    void rejectsWholeAnswerIfOneClaimIsInvalid() throws Exception {
        reply(200, completion(List.of(
                claim("有效的第一条", A, "通过恒等映射"),
                claim("非法的第二条", "missing", "通过恒等映射"))));
        assertFallback(ask(), "invalid_citation");
    }

    @Test
    void rejectsMissingCitationFieldsAndInventedInlineCitations() throws Exception {
        reply(200, completion(List.of(Map.of("text", "缺少证据字段"))));
        assertFallback(ask(), "invalid_response");
        replaceReply(200, completion(List.of(claim("训练更容易 [99]", A, "帮助训练深层网络"))));
        assertFallback(ask(), "invalid_response");
        replaceReply(200, completion(List.of(claim("<script>unexpected</script>", A, "帮助训练深层网络"))));
        assertFallback(ask(), "invalid_response");
    }

    @Test
    void returnsExplicitInsufficientEvidenceWhenModelAbstains() throws Exception {
        reply(200, completion(List.of()));
        assertFallback(ask(), "insufficient_evidence");
    }

    @Test
    void rejectsMalformedTrailingAndDuplicateJson() throws Exception {
        reply(200, "{not-json");
        assertFallback(ask(), "invalid_response");
        replaceReply(200, envelope("{\"claims\":[]} {\"claims\":[]}", "stop"));
        assertFallback(ask(), "invalid_response");
        replaceReply(200, envelope("{\"claims\":[],\"claims\":[]}", "stop"));
        assertFallback(ask(), "invalid_response");
        replaceReply(200, envelope("```json\n{\"claims\":[]}\n```", "stop"));
        assertFallback(ask(), "invalid_response");
    }

    @Test
    void rejectsTruncatedGenerationAndToolCalls() throws Exception {
        String claims = json.writeValueAsString(Map.of("claims", List.of(claim("有效文字", A, "通过恒等映射"))));
        reply(200, envelope(claims, "length"));
        assertFallback(ask(), "invalid_response");
        replaceReply(200, json.writeValueAsString(Map.of("choices", List.of(Map.of(
                "finish_reason", "stop", "message", Map.of("content", claims, "tool_calls", List.of()))))));
        assertFallback(ask(), "invalid_response");
    }

    @Test
    void neverReturnsProviderErrorsOrCredentials() throws Exception {
        reply(503, "private diagnostic test-key https://provider/private");
        ModelGateway.Generation result = ask();
        assertFallback(result, "provider_error");
        assertFalse(result.toString().contains("test-key"));
        assertFalse(result.toString().contains("private"));
    }

    @Test
    void timesOutBeforeResponseHeaders() {
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            sleep(2500);
            exchange.close();
        });
        assertTimeout(Duration.ofSeconds(4), () -> assertFallback(
                gateway(1).generate("问题", evidence()), "timeout"));
    }

    @Test
    void deadlineAlsoCoversSlowResponseBody() {
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            sleep(2500);
            exchange.close();
        });
        assertTimeout(Duration.ofSeconds(4), () -> assertFallback(
                gateway(1).generate("问题", evidence()), "timeout"));
    }

    @Test
    void rejectsOversizedBodyWithoutReturningItsContent() throws Exception {
        reply(200, "x".repeat(140 * 1024));
        assertFallback(ask(), "provider_error");
    }

    @Test
    void incompleteHttpBodyFallsBack() {
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, 500);
            exchange.getResponseBody().write("{\"choices\":[]}".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        assertFallback(ask(), "provider_error");
    }

    @Test
    void doesNotFollowRedirectsWithAuthorization() {
        AtomicInteger redirected = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Location", baseUrl + "/redirected");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.createContext("/v1/redirected", exchange -> {
            redirected.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        assertFallback(ask(), "provider_error");
        assertEquals(0, redirected.get());
    }

    @Test
    void enforcesSafeConfiguredEndpointAndTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new ModelGateway("http://example.com/v1", "", "m", 2, json));
        assertThrows(IllegalArgumentException.class, () -> new ModelGateway("https://user:pass@example.com/v1", "", "m", 2, json));
        assertThrows(IllegalArgumentException.class, () -> new ModelGateway("https://example.com/v1?key=secret", "", "m", 2, json));
        assertThrows(IllegalArgumentException.class, () -> new ModelGateway(baseUrl, "", "m", 0, json));
        assertThrows(IllegalArgumentException.class, () -> new ModelGateway(baseUrl, "", "m", 61, json));
        assertDoesNotThrow(() -> new ModelGateway("https://example.com/v1/", "", "m", 2, json));
    }

    @Test
    void exposesConfigurationCapabilityWithoutAProviderRequestOrCredentialFields() {
        reply(500, "should not be requested");
        assertFalse(new ModelGateway("", "test-key", "test-model", 7, json).isConfigured());
        assertFalse(new ModelGateway(baseUrl, "test-key", "", 7, json).isConfigured());
        ModelGateway configured = new ModelGateway(baseUrl, "", "local-model", 60, json);
        assertTrue(configured.isConfigured());
        assertEquals(60, configured.timeoutSeconds());
        assertEquals(0, requests.get());
    }

    private ModelGateway gateway(int seconds) {
        return new ModelGateway(baseUrl, "test-key", "test-model", seconds, json);
    }

    private ModelGateway.Generation ask() {
        return gateway(2).generate("残差连接的作用？", evidence());
    }

    private List<ModelGateway.ModelEvidence> evidence() {
        return List.of(new ModelGateway.ModelEvidence(A, ORIGINAL));
    }

    private Map<String, String> claim(String text, String chunkId, String quote) {
        return Map.of("text", text, "chunkId", chunkId, "quote", quote);
    }

    private String completion(List<?> claims) throws Exception {
        return envelope(json.writeValueAsString(Map.of("claims", claims)), "stop");
    }

    private String envelope(String content, String finishReason) throws Exception {
        return json.writeValueAsString(Map.of("choices", List.of(Map.of(
                "message", Map.of("content", content), "finish_reason", finishReason))));
    }

    private void reply(int status, String body) {
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            capturedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            send(exchange, status, body);
        });
    }

    private void replaceReply(int status, String body) {
        server.removeContext("/v1/chat/completions");
        reply(status, body);
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void assertFallback(ModelGateway.Generation result, String reason) {
        assertFalse(result.generated());
        assertEquals(reason, result.reason());
        assertTrue(result.answer().isEmpty());
        assertTrue(result.citationIds().isEmpty());
        assertTrue(result.claims().isEmpty());
    }
}
