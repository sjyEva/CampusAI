package edu.campusai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import edu.campusai.auth.DemoAccounts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the real servlet container, including async security redispatch and unbuffered HTTP reads. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:rag_stream_http;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "campusai.dev-password=StreamTest-Password",
        "campusai.rag.model.name=test-stream-model", "campusai.rag.model.timeout-seconds=4"
})
@Timeout(12)
class RagStreamingIntegrationTest {
    private static final String ORIGINAL = "残差连接通过恒等映射为梯度传播提供路径，帮助训练深层网络。";
    private static final Provider PROVIDER = new Provider();
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private RagMaterialService materials;
    @Autowired private DemoAccounts accounts;
    @Autowired private JdbcClient jdbc;
    private final HttpClient client = HttpClient.newHttpClient();
    private String token;
    private String materialId;

    @DynamicPropertySource static void config(DynamicPropertyRegistry properties) { properties.add("campusai.rag.model.base-url", PROVIDER::url); }
    @AfterAll static void stop() { PROVIDER.server.stop(0); PROVIDER.executor.shutdownNow(); }
    @BeforeEach void setup() throws Exception {
        PROVIDER.release = new CountDownLatch(1); PROVIDER.mode.set("ok"); PROVIDER.requests.set(0);
        jdbc.sql("DELETE FROM rag_material").update();
        jdbc.sql("MERGE INTO campus_course_enrollment KEY(course_id,student_username) VALUES(1,'student')").update();
        materialId = materials.upload(accounts.find("teacher"), 1, "stream.txt", ORIGINAL.getBytes(StandardCharsets.UTF_8)).id();
        materials.publish(accounts.find("teacher"), 1, materialId, true, 1);
        HttpResponse<String> login = client.send(HttpRequest.newBuilder(uri("/login")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username", "student", "password", "StreamTest-Password")))).build(), HttpResponse.BodyHandlers.ofString());
        token = json.readTree(login.body()).path("token").asText(); assertFalse(token.isBlank());
    }

    @Test void actualHttpStreamDeliversDraftBeforeProviderFinishesThenValidatedDone() throws Exception {
        try (var input = open("残差连接")) {
            Event first = untilDelta(input);
            assertEquals("残差", first.data.path("text").asText());
            assertEquals(1, PROVIDER.release.getCount(), "Provider is still blocked when client reads first text");
            PROVIDER.release.countDown();
            List<Event> rest = remaining(input);
            Event done = rest.stream().filter(event -> event.name.equals("done")).findFirst().orElseThrow();
            assertEquals("generated", done.data.path("status").asText());
            assertEquals("残差连接有助于训练。 [1]", done.data.path("answer").asText());
            assertEquals(ORIGINAL, done.data.path("citations").get(0).path("text").asText());
            assertFalse(rest.stream().anyMatch(event -> event.name.equals("error")), "Async completion must not append an authentication failure");
        } finally { PROVIDER.release.countDown(); }
    }

    @Test void invalidCitationProducesFallbackDoneThatContainsNoGeneratedDraft() throws Exception {
        PROVIDER.mode.set("invalid");
        try (var input = open("残差连接")) {
            untilDelta(input); PROVIDER.release.countDown();
            Event done = remaining(input).stream().filter(event -> event.name.equals("done")).findFirst().orElseThrow();
            assertEquals("retrieval_only", done.data.path("status").asText());
            assertEquals("invalid_citation", done.data.path("reason").asText());
            assertTrue(done.data.path("claims").isEmpty()); assertTrue(done.data.path("citations").isEmpty());
            assertFalse(done.data.path("answer").asText().contains("有助于训练"));
        } finally { PROVIDER.release.countDown(); }
    }

    @Test void unpublishingDuringGenerationStopsFurtherDeltasAndSendsSafeTerminalError() throws Exception {
        try (var input = open("残差连接")) {
            untilDelta(input);
            materials.publish(accounts.find("teacher"), 1, materialId, false, 1);
            PROVIDER.release.countDown();
            List<Event> rest = remaining(input);
            assertFalse(rest.stream().anyMatch(event -> event.name.equals("delta") || event.name.equals("done")));
            Event error = rest.stream().filter(event -> event.name.equals("error")).findFirst().orElseThrow();
            assertEquals(409, error.data.path("code").asInt());
            assertFalse(error.data.toString().contains(ORIGINAL));
        } finally { PROVIDER.release.countDown(); }
    }

    @Test void revokedEnrollmentDuringGenerationStopsFurtherDeltas() throws Exception {
        try (var input = open("残差连接")) {
            untilDelta(input);
            jdbc.sql("DELETE FROM campus_course_enrollment WHERE course_id=1 AND student_username='student'").update();
            PROVIDER.release.countDown();
            List<Event> rest = remaining(input);
            assertFalse(rest.stream().anyMatch(event -> event.name.equals("delta") || event.name.equals("done")));
            Event error = rest.stream().filter(event -> event.name.equals("error")).findFirst().orElseThrow();
            assertEquals(404, error.data.path("code").asInt());
        } finally { PROVIDER.release.countDown(); }
    }

    @Test void noEvidenceFinishesWithoutProviderAndAnonymousAccessIsRejected() throws Exception {
        try (var input = open("火星殖民地人口")) {
            Event done = remaining(input).stream().filter(event -> event.name.equals("done")).findFirst().orElseThrow();
            assertEquals("no_evidence", done.data.path("status").asText()); assertEquals(0, PROVIDER.requests.get());
        }
        var response = client.send(HttpRequest.newBuilder(uri("/campusai/courses/1/rag/ask/stream"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"残差连接\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, json.readTree(response.body()).path("code").asInt()); assertEquals(0, PROVIDER.requests.get());
    }

    private BufferedReader open(String question) throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri("/campusai/courses/1/rag/ask/stream"))
                .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + token)
                .header("Accept", "text/event-stream").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("question", question)))).build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        return new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
    }
    private Event untilDelta(BufferedReader reader) throws Exception {
        Event event; while ((event = next(reader)) != null) { if (event.name.equals("delta")) return event; }
        throw new AssertionError("Expected provisional delta before stream completion");
    }
    private List<Event> remaining(BufferedReader reader) throws Exception {
        List<Event> events = new ArrayList<>(); Event event; while ((event = next(reader)) != null) events.add(event); return events;
    }
    private Event next(BufferedReader reader) throws Exception {
        String line, name = "message"; StringBuilder data = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("event:")) name = line.substring(6).strip();
            if (line.startsWith("data:")) data.append(line.substring(5).stripLeading());
            if (line.isEmpty() && !data.isEmpty()) return new Event(name, json.readTree(data.toString()));
        }
        return null;
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private record Event(String name, JsonNode data) { }

    private static final class Provider {
        final ObjectMapper json = new ObjectMapper(); final HttpServer server;
        final ExecutorService executor = Executors.newCachedThreadPool();
        final AtomicReference<String> mode = new AtomicReference<>("ok"); final AtomicInteger requests = new AtomicInteger();
        volatile CountDownLatch release = new CountDownLatch(1);
        Provider() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(executor);
                server.createContext("/v1/chat/completions", exchange -> {
                    requests.incrementAndGet(); JsonNode body = json.readTree(exchange.getRequestBody());
                    assertTrue(body.path("stream").asBoolean());
                    JsonNode evidence = json.readTree(body.path("messages").get(1).path("content").asText()).path("evidence").get(0);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
                    try (var out = exchange.getResponseBody()) {
                        delta(out, "{\"claims\":[{\"text\":\"残差"); release.await(6, TimeUnit.SECONDS);
                        String quote = mode.get().equals("invalid") ? "找不到的引文" : evidence.path("text").asText();
                        delta(out, "连接有助于训练。\",\"chunkId\":" + json.writeValueAsString(evidence.path("id").asText()) + ",\"quote\":" + json.writeValueAsString(quote) + "}]}");
                        out.write("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)); out.flush();
                    } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                    catch (java.io.IOException ignored) { }
                }); server.start();
            } catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
        }
        void delta(OutputStream out, String value) throws java.io.IOException {
            out.write(("data: " + json.writeValueAsString(Map.of("choices", List.of(Map.of("delta", Map.of("content", value))))) + "\n\n").getBytes(StandardCharsets.UTF_8)); out.flush();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
    }
}
