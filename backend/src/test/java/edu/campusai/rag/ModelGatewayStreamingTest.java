package edu.campusai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ModelGatewayStreamingTest {
    private final ObjectMapper json = new ObjectMapper();
    private final String original = "残差连接通过恒等映射帮助训练深层网络。";
    private final List<ModelGateway.ModelEvidence> evidence = List.of(new ModelGateway.ModelEvidence("chunk-a", original));
    private HttpServer server;
    private ExecutorService executor;
    private final AtomicReference<JsonNode> request = new AtomicReference<>();

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor); server.start();
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
    private ModelGateway gateway(int timeout) {
        return new ModelGateway("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-only-key", "test-model", timeout, json);
    }
    private void handler(Body body) {
        server.createContext("/v1/chat/completions", exchange -> {
            request.set(json.readTree(exchange.getRequestBody()));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) { body.send(out); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            catch (IOException ignored) { /* Cancellation intentionally closes the client connection. */ }
        });
    }
    private void delta(OutputStream out, String content) throws IOException {
        String data = json.writeValueAsString(Map.of("choices", List.of(Map.of("delta", Map.of("content", content)))));
        byte[] packet = ("data: " + data + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        // Exercise both UTF-8 byte boundaries and SSE CRLF framing across network writes.
        for (int start = 0; start < packet.length; start += 3) out.write(packet, start, Math.min(3, packet.length - start));
        out.flush();
    }
    private void finish(OutputStream out) throws IOException {
        out.write(("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8)); out.flush();
    }
    private String tail(String quote) throws IOException {
        return ",\"chunkId\":\"chunk-a\",\"quote\":" + json.writeValueAsString(quote) + "}]}";
    }

    @Test void emitsFirstActualDeltaBeforeProviderFinishesAndValidatesFinalCitations() throws Exception {
        CountDownLatch first = new CountDownLatch(1), release = new CountDownLatch(1);
        handler(out -> {
            delta(out, "{\"claims\":[{\"text\":\"残差");
            release.await(4, TimeUnit.SECONDS);
            delta(out, "连接帮助训练。\"" + tail(original)); finish(out);
        });
        List<String> deltas = new java.util.concurrent.CopyOnWriteArrayList<>();
        Future<ModelGateway.Generation> result = executor.submit(() -> gateway(5).generateStream("残差连接", evidence,
                text -> { deltas.add(text); first.countDown(); }, new ModelGateway.StreamCancellation()));
        try {
            assertTrue(first.await(3, TimeUnit.SECONDS), "A real arriving delta must be emitted while provider is blocked");
            assertEquals("残差", String.join("", deltas)); assertFalse(result.isDone());
            release.countDown();
            var generated = result.get(3, TimeUnit.SECONDS);
            assertTrue(request.get().path("stream").asBoolean());
            assertTrue(generated.generated()); assertEquals("残差连接帮助训练。", String.join("", deltas));
            assertEquals(original, generated.claims().get(0).quote());
        } finally { release.countDown(); result.cancel(true); }
    }

    @Test void invalidFinalCitationWithdrawsProvisionalTextInsteadOfApprovingIt() throws Exception {
        handler(out -> { delta(out, "{\"claims\":[{\"text\":\"已有草稿。\"" + tail("并不存在的逐字依据")); finish(out); });
        List<String> deltas = new ArrayList<>();
        var result = gateway(3).generateStream("残差连接", evidence, deltas::add, new ModelGateway.StreamCancellation());
        assertFalse(deltas.isEmpty()); assertFalse(result.generated());
        assertEquals("invalid_citation", result.reason()); assertTrue(result.answer().isEmpty()); assertTrue(result.claims().isEmpty());
    }

    @Test void unicodeEscapesAndQuotesSplitAcrossContentDeltasRemainWholeAndDoNotLeakMetadata() throws Exception {
        String text = "解释\"引文\"与😀。";
        String content = "{\"claims\":[{\"text\":\"解释\\\"引文\\\"与\\uD83D\\uDE00。\"" + tail(original);
        handler(out -> { for (int i = 0; i < content.length(); i++) delta(out, content.substring(i, i + 1)); finish(out); });
        List<String> deltas = new ArrayList<>();
        var result = gateway(3).generateStream("残差连接", evidence, deltas::add, new ModelGateway.StreamCancellation());
        assertTrue(result.generated()); assertEquals(text, String.join("", deltas));
        assertTrue(deltas.size() > 3);
        for (String part : deltas) {
            assertFalse(Character.isHighSurrogate(part.charAt(part.length() - 1)));
            assertFalse(Character.isLowSurrogate(part.charAt(0)));
        }
        assertFalse(String.join("", deltas).contains("chunk-a")); assertFalse(String.join("", deltas).contains(original));
    }

    @Test void deadlineClosesAProviderThatHasSentHeadersButNeverFinishes() {
        handler(out -> { delta(out, "{\"claims\":[{\"text\":\"部分"); Thread.sleep(5000); });
        var cancellation = new ModelGateway.StreamCancellation();
        ModelGateway.Generation result = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> gateway(1).generateStream("残差连接", evidence, ignored -> {}, cancellation));
        assertFalse(result.generated()); assertEquals("timeout", result.reason());
        assertFalse(cancellation.isClientCancelled(), "A provider timeout still allows the server to send a final fallback");
    }

    @Test void clientCancellationStopsReadingWithoutWaitingForProviderCompletion() {
        handler(out -> { delta(out, "{\"claims\":[{\"text\":\"部分"); Thread.sleep(5000); });
        var cancellation = new ModelGateway.StreamCancellation();
        var result = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> gateway(5).generateStream("残差连接", evidence,
                ignored -> cancellation.cancel(), cancellation));
        assertFalse(result.generated()); assertEquals("interrupted", result.reason()); assertTrue(cancellation.isClientCancelled());
    }

    @Test void incompleteOrDuplicateJsonIsNotAcceptedAsFinalAnswer() throws Exception {
        handler(out -> { delta(out, "{\"claims\":[{\"text\":\"草稿。\"" + tail(original)); });
        var truncated = gateway(2).generateStream("残差连接", evidence, ignored -> {}, new ModelGateway.StreamCancellation());
        assertFalse(truncated.generated()); assertEquals("invalid_response", truncated.reason());
        assertThrows(IllegalArgumentException.class, () -> StreamingClaimText.preview("{\"claims\":[{\"text\":\"先\",\"text\":\"后\"}]}"));
    }

    @FunctionalInterface private interface Body { void send(OutputStream out) throws IOException, InterruptedException; }
}
