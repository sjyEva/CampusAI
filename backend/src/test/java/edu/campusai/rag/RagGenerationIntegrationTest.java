package edu.campusai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises the real protected HTTP entry point and a controlled external HTTP provider. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:campusai_rag_generation_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "campusai.dev-password=CampusTest-Password",
        "campusai.rag.model.name=local-test-model",
        "campusai.rag.model.api-key=test-only-secret",
        "campusai.rag.model.timeout-seconds=3"
})
@AutoConfigureMockMvc
class RagGenerationIntegrationTest {
    private static final String ROOT = "/campusai/courses/1/rag";
    private static final String TEXT = "残差连接通过恒等映射为梯度传播提供直接路径，帮助训练深层网络。";
    private static final String GENERATED = "残差连接有助于训练深层网络。";
    private static final Provider PROVIDER = new Provider();

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcClient jdbc;
    private String student;
    private String teacher;

    @DynamicPropertySource
    static void modelConfiguration(DynamicPropertyRegistry properties) {
        properties.add("campusai.rag.model.base-url", PROVIDER::baseUrl);
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.close();
    }

    @BeforeEach
    void prepare() throws Exception {
        PROVIDER.reset();
        jdbc.sql("DELETE FROM rag_material").update();
        jdbc.sql("MERGE INTO campus_course_enrollment KEY(course_id,student_username) VALUES(1,'student')").update();
        teacher = login("teacher");
        student = login("student");
    }

    @Test
    void generatedAnswerCarriesCurrentSourcesAndExcludesOtherCourses() throws Exception {
        String material = uploadAndPublish(1, "notes.txt", TEXT);
        uploadAndPublish(2, "private.txt", "残差连接的跨课程机密内容，不得交给第一门课程的模型请求。");
        JsonNode answer = ask("残差连接").path("data");
        assertEquals("generated", answer.path("status").asText());
        assertEquals(GENERATED + " [1]", answer.path("answer").asText());
        assertEquals(1, answer.path("citations").size());
        JsonNode source = answer.path("citations").get(0);
        assertEquals(material, source.path("materialId").asText());
        assertEquals(1, source.path("courseId").asLong());
        assertEquals(TEXT, source.path("text").asText());
        JsonNode claim = answer.path("claims").get(0);
        assertNotNull(claim);
        assertEquals(GENERATED, claim.path("text").asText());
        assertEquals(1, claim.path("citationNumber").asInt());
        assertEquals(source.path("chunkId").asText(), claim.path("chunkId").asText());
        assertEquals(claim.path("quote").asText(), TEXT.substring(claim.path("quoteStart").asInt(), claim.path("quoteEnd").asInt()));
        assertFalse(PROVIDER.lastInput.get().contains("跨课程机密"));
        mvc.perform(get(ROOT + "/sources/" + source.path("chunkId").asText())
                        .header(HttpHeaders.AUTHORIZATION, student))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.text").value(TEXT));
    }

    @Test
    void noEvidenceNeverCallsProviderAndProviderFailureRetainsOnlyVerifiedOriginals() throws Exception {
        uploadAndPublish(1, "notes.txt", TEXT);
        JsonNode absent = ask("火星殖民地人口").path("data");
        assertEquals("no_evidence", absent.path("status").asText());
        assertEquals(0, PROVIDER.requests.get());
        PROVIDER.mode.set("error");
        JsonNode failed = ask("残差连接").path("data");
        assertEquals("retrieval_only", failed.path("status").asText());
        assertEquals("provider_error", failed.path("reason").asText());
        assertEquals(0, failed.path("citations").size());
        assertEquals(TEXT, failed.path("evidence").get(0).path("source").path("text").asText());
        assertFalse(failed.toString().contains("test-only-secret"));
        assertFalse(failed.path("answer").asText().contains(GENERATED));
        assertTrue(failed.path("claims").isEmpty());
    }

    @Test
    void foreignCitationIsNeverReturnedAndAbstentionIsExplicit() throws Exception {
        uploadAndPublish(1, "notes.txt", TEXT);
        PROVIDER.mode.set("foreign");
        JsonNode invalid = ask("残差连接").path("data");
        assertEquals("retrieval_only", invalid.path("status").asText());
        assertEquals("invalid_citation", invalid.path("reason").asText());
        assertEquals(0, invalid.path("citations").size());
        assertFalse(invalid.toString().contains("foreign-chunk"));
        PROVIDER.mode.set("abstain");
        JsonNode abstained = ask("残差连接").path("data");
        assertEquals("no_evidence", abstained.path("status").asText());
        assertEquals("insufficient_evidence", abstained.path("reason").asText());
        assertEquals(0, abstained.path("citations").size());
        assertEquals(TEXT, abstained.path("evidence").get(0).path("source").path("text").asText());
    }

    @Test
    void unpublishedSourcesAreDiscardedAfterModelReturns() throws Exception {
        String material = uploadAndPublish(1, "notes.txt", TEXT);
        JsonNode result = askDuring(() -> publication(1, material, false));
        assertChangedWithoutStaleText(result);
    }

    @Test
    void replacedVersionIsDiscardedEvenWhenReplacementIsPublished() throws Exception {
        String material = uploadAndPublish(1, "notes.txt", TEXT);
        JsonNode result = askDuring(() -> {
            MockMultipartFile replacement = new MockMultipartFile("file", "new.txt", "text/plain",
                    "软件测试验证程序行为是否满足需求。".getBytes(StandardCharsets.UTF_8));
            mvc.perform(multipart(ROOT + "/materials/" + material).file(replacement)
                            .with(request -> { request.setMethod("PUT"); return request; })
                            .header(HttpHeaders.AUTHORIZATION, teacher))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.version").value(2));
            publication(1, material, true);
        });
        assertChangedWithoutStaleText(result);
    }

    @Test
    void deletedSourcesAreDiscardedAfterModelReturns() throws Exception {
        String material = uploadAndPublish(1, "notes.txt", TEXT);
        JsonNode result = askDuring(() -> mvc.perform(delete(ROOT + "/materials/" + material)
                        .header(HttpHeaders.AUTHORIZATION, teacher))
                .andExpect(jsonPath("$.code").value(200)));
        assertChangedWithoutStaleText(result);
    }

    @Test
    void revokedEnrollmentIsCheckedAgainAfterModelReturns() throws Exception {
        uploadAndPublish(1, "notes.txt", TEXT);
        JsonNode result = askDuring(() -> jdbc.sql(
                "DELETE FROM campus_course_enrollment WHERE course_id=1 AND student_username='student'").update());
        assertEquals(404, result.path("code").asInt());
        assertFalse(result.has("data"));
        assertFalse(result.toString().contains(TEXT));
        assertFalse(result.toString().contains(GENERATED));
    }

    @Test
    void staleSourcesAreAlsoDiscardedOnProviderFailure() throws Exception {
        String material = uploadAndPublish(1, "notes.txt", TEXT);
        PROVIDER.mode.set("error");
        assertChangedWithoutStaleText(askDuring(() -> publication(1, material, false)));
    }

    private void assertChangedWithoutStaleText(JsonNode response) {
        assertEquals(200, response.path("code").asInt());
        JsonNode data = response.path("data");
        assertEquals("sources_changed", data.path("status").asText());
        assertEquals(0, data.path("citations").size());
        assertEquals(0, data.path("evidence").size());
        assertFalse(data.toString().contains(TEXT));
        assertFalse(data.toString().contains(GENERATED));
    }

    private JsonNode askDuring(CheckedAction mutation) throws Exception {
        Gate gate = new Gate();
        PROVIDER.gate.set(gate);
        ExecutorService requestThread = Executors.newSingleThreadExecutor();
        Future<JsonNode> response = requestThread.submit(() -> ask("残差连接"));
        try {
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS), "provider must receive evidence before mutation");
            mutation.run();
            gate.release.countDown();
            return response.get(5, TimeUnit.SECONDS);
        } finally {
            gate.release.countDown();
            response.cancel(true);
            requestThread.shutdownNow();
        }
    }

    private JsonNode ask(String question) throws Exception {
        MvcResult result = mvc.perform(post(ROOT + "/ask").header(HttpHeaders.AUTHORIZATION, student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("question", question))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private String uploadAndPublish(long course, String name, String text) throws Exception {
        MvcResult uploaded = mvc.perform(multipart("/campusai/courses/" + course + "/rag/materials")
                        .file(new MockMultipartFile("file", name, "text/plain", text.getBytes(StandardCharsets.UTF_8)))
                        .header(HttpHeaders.AUTHORIZATION, teacher))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.parseStatus").value("READY")).andReturn();
        String id = json.readTree(uploaded.getResponse().getContentAsByteArray()).path("data").path("id").asText();
        publication(course, id, true);
        return id;
    }

    private void publication(long course, String id, boolean published) throws Exception {
        mvc.perform(patch("/campusai/courses/" + course + "/rag/materials/" + id + "/publication")
                        .header(HttpHeaders.AUTHORIZATION, teacher).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("published", published))))
                .andExpect(jsonPath("$.code").value(200));
    }

    private String login(String username) throws Exception {
        MvcResult result = mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("username", username, "password", "CampusTest-Password"))))
                .andExpect(jsonPath("$.code").value(200)).andReturn();
        return "Bearer " + json.readTree(result.getResponse().getContentAsByteArray()).path("token").asText();
    }

    @FunctionalInterface
    private interface CheckedAction { void run() throws Exception; }

    private static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
    }

    private static final class Provider implements AutoCloseable {
        private final ObjectMapper json = new ObjectMapper();
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicReference<String> lastInput = new AtomicReference<>("");
        private final AtomicReference<String> mode = new AtomicReference<>("success");
        private final AtomicReference<Gate> gate = new AtomicReference<>();

        Provider() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.setExecutor(executor);
                server.createContext("/v1/chat/completions", this::respond);
                server.start();
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot start test provider", exception);
            }
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }

        void reset() {
            requests.set(0);
            lastInput.set("");
            mode.set("success");
            gate.set(null);
        }

        private void respond(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            JsonNode request = json.readTree(exchange.getRequestBody());
            String input = request.path("messages").get(1).path("content").asText();
            lastInput.set(input);
            JsonNode evidence = json.readTree(input).path("evidence").get(0);
            Gate blocked = gate.get();
            if (blocked != null) {
                blocked.entered.countDown();
                try { blocked.release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
            if (mode.get().equals("error")) {
                send(exchange, 503, "test-only-secret provider diagnostic");
                return;
            }
            String id = mode.get().equals("foreign") ? "foreign-chunk" : evidence.path("id").asText();
            List<?> claims = mode.get().equals("abstain") ? List.of() : List.of(Map.of(
                    "text", GENERATED, "chunkId", id, "quote", evidence.path("text").asText()));
            String content = json.writeValueAsString(Map.of("claims", claims));
            send(exchange, 200, json.writeValueAsString(Map.of("choices", List.of(Map.of(
                    "finish_reason", "stop", "message", Map.of("content", content))))));
        }

        private void send(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
