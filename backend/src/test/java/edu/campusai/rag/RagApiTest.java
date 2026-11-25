package edu.campusai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:campusai_rag_api;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "campusai.dev-password=CampusTest-Password",
        "campusai.rag.model.base-url=", "campusai.rag.model.name=", "campusai.rag.model.api-key=",
        "campusai.rag.model.timeout-seconds=60"
})
@AutoConfigureMockMvc
class RagApiTest {
    private static final String BASE = "/campusai/courses/1/rag";
    private static final String TEXT = "残差连接把输入与变换后的输出相加，为梯度提供更直接的传播路径。";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    String teacher;
    String student;

    @BeforeEach
    void prepare() throws Exception {
        jdbc.sql("DELETE FROM rag_chunk").update();
        jdbc.sql("DELETE FROM rag_material").update();
        teacher = login("teacher");
        student = login("student");
    }

    @Test
    void publicationSearchSourceFallbackAndUnpublicationUseRealStoredText() throws Exception {
        JsonNode uploaded = upload("lesson.txt", TEXT.getBytes(StandardCharsets.UTF_8));
        String id = uploaded.path("id").asText();
        assertEquals("READY", uploaded.path("parseStatus").asText());
        assertFalse(uploaded.path("published").asBoolean());
        assertEquals(1, uploaded.path("version").asInt());
        assertTrue(search().path("hits").isEmpty());
        check(get(BASE + "/materials/" + id + "/file"), student, 404);
        String preview = BASE + "/materials/" + id + "/preview";
        check(get(preview).param("version", "1"), student, 404);
        publish(id, true);
        JsonNode visible = data(get(preview).param("version", "1").param("limit", "1"), student);
        assertEquals(TEXT, visible.path("sources").get(0).path("text").asText());
        assertTrue(visible.path("nextOffset").isNull());
        check(get(preview).param("version", "1"), login("student2"), 404);
        check(get(preview).param("version", "2"), student, 409);
        JsonNode hit = search().path("hits").get(0).path("source");
        assertEquals(TEXT, hit.path("text").asText());
        assertEquals(1, hit.path("paragraphStart").asInt());
        assertTrue(hit.path("pageNumber").isNull());
        String chunkId = hit.path("chunkId").asText();
        check(get(BASE + "/sources/" + chunkId), student, 200);
        assertArrayEquals(TEXT.getBytes(StandardCharsets.UTF_8), mvc.perform(get(BASE + "/materials/" + id + "/file")
                .header(HttpHeaders.AUTHORIZATION, student)).andReturn().getResponse().getContentAsByteArray());
        JsonNode answer = data(post(BASE + "/ask").contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"残差连接\"}"), student);
        assertEquals("retrieval_only", answer.path("status").asText());
        assertEquals("unconfigured", answer.path("reason").asText());
        assertEquals(TEXT, answer.path("evidence").get(0).path("source").path("text").asText());
        assertTrue(answer.path("citations").isEmpty());
        publish(id, false);
        assertEquals("no_evidence", search().path("status").asText());
        check(get(BASE + "/sources/" + chunkId), student, 404);
        check(get(BASE + "/materials/" + id + "/file"), student, 404);
        check(get(preview).param("version", "1"), student, 404);
    }

    @Test
    void retryIsIdempotentAndReplacementInvalidatesOldSourcesAndPublication() throws Exception {
        String id = upload("lesson.txt", TEXT.getBytes(StandardCharsets.UTF_8)).path("id").asText();
        publish(id, true);
        String oldChunk = search().path("hits").get(0).path("source").path("chunkId").asText();
        JsonNode retry = data(post(BASE + "/materials/" + id + "/reprocess"), teacher);
        assertEquals(1, retry.path("version").asInt());
        assertTrue(retry.path("published").asBoolean());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM rag_chunk WHERE material_id=:id").param("id", id).query(Integer.class).single());
        MockMultipartFile replacement = new MockMultipartFile("file", "lesson.txt", "text/plain", "软件测试通过执行程序发现缺陷。".getBytes(StandardCharsets.UTF_8));
        JsonNode updated = data(multipart(BASE + "/materials/" + id).file(replacement).with(request -> { request.setMethod("PUT"); return request; }), teacher);
        assertEquals(2, updated.path("version").asInt());
        assertFalse(updated.path("published").asBoolean());
        check(get(BASE + "/sources/" + oldChunk), student, 404);
        publish(id, true);
        assertEquals("no_evidence", search().path("status").asText());
        JsonNode newHits = data(get(BASE + "/search").param("q", "软件测试"), student).path("hits");
        assertFalse(newHits.isEmpty());
        check(delete(BASE + "/materials/" + id), teacher, 200);
        check(get(BASE + "/sources/" + newHits.get(0).path("source").path("chunkId").asText()), student, 404);
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM rag_chunk").query(Integer.class).single());
    }

    @Test
    void malformedReplacementLeavesFailedStateAndNoOldOrPartialChunks() throws Exception {
        String id = upload("lesson.txt", TEXT.getBytes(StandardCharsets.UTF_8)).path("id").asText();
        publish(id, true);
        MockMultipartFile broken = new MockMultipartFile("file", "broken.pdf", "application/pdf", "not pdf".getBytes(StandardCharsets.UTF_8));
        JsonNode result = data(multipart(BASE + "/materials/" + id).file(broken).with(request -> { request.setMethod("PUT"); return request; }), teacher);
        assertEquals("FAILED", result.path("parseStatus").asText());
        assertFalse(result.path("published").asBoolean());
        assertEquals(0, result.path("chunkCount").asInt());
        assertFalse(result.path("errorMessage").asText().isBlank());
        check(patch(BASE + "/materials/" + id + "/publication").contentType(MediaType.APPLICATION_JSON).content("{\"published\":true}"), teacher, 409);
        assertTrue(search().path("hits").isEmpty());
        assertEquals("FAILED", data(post(BASE + "/materials/" + id + "/reprocess"), teacher).path("parseStatus").asText());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM rag_chunk").query(Integer.class).single());
    }

    @Test
    void studentsCannotManageOrReadAnotherCourseAndTeachersCannotManageOthersCourses() throws Exception {
        String id = upload("lesson.txt", TEXT.getBytes(StandardCharsets.UTF_8)).path("id").asText();
        publish(id, true);
        String chunk = search().path("hits").get(0).path("source").path("chunkId").asText();
        String other = login("student2");
        check(get(BASE + "/search").param("q", "残差连接"), other, 404);
        check(post(BASE + "/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"残差连接\"}"), other, 404);
        check(get(BASE + "/sources/" + chunk), other, 404);
        check(get(BASE + "/materials/" + id + "/file"), other, 404);
        check(get("/campusai/courses/2/rag/sources/" + chunk), other, 404);
        check(delete(BASE + "/materials/" + id), student, 403);
        check(post(BASE + "/materials/" + id + "/reprocess"), student, 403);
        check(patch(BASE + "/materials/" + id + "/publication").contentType(MediaType.APPLICATION_JSON).content("{\"published\":false}"), student, 403);
        check(multipart(BASE + "/materials").file(new MockMultipartFile("file", "x.txt", "text/plain", TEXT.getBytes(StandardCharsets.UTF_8))), student, 403);
        jdbc.sql("INSERT INTO campus_course(id,course_code,name,description,teacher_username) VALUES(99,'TEST99','Other','Other','another-teacher')").update();
        try {
            check(multipart("/campusai/courses/99/rag/materials").file(new MockMultipartFile("file", "x.txt", "text/plain", TEXT.getBytes(StandardCharsets.UTF_8))), teacher, 404);
            data(multipart("/campusai/courses/99/rag/materials").file(new MockMultipartFile("file", "x.txt", "text/plain", TEXT.getBytes(StandardCharsets.UTF_8))), login("admin"));
        } finally {
            jdbc.sql("DELETE FROM rag_material WHERE course_id=99").update();
            jdbc.sql("DELETE FROM campus_course WHERE id=99").update();
        }
    }

    @Test
    void invalidInputsStayInsideSharedEnvelopeAndNoMatchIsExplicit() throws Exception {
        check(get(BASE + "/search").param("q", ""), student, 400);
        check(get(BASE + "/search"), student, 400);
        check(get(BASE + "/search").param("q", "x").param("topK", "11"), student, 400);
        check(post(BASE + "/ask").contentType(MediaType.APPLICATION_JSON).content("{}"), student, 400);
        check(post(BASE + "/ask").contentType(MediaType.APPLICATION_JSON).content("{broken"), student, 400);
        check(multipart(BASE + "/materials"), teacher, 400);
        check(multipart(BASE + "/materials").file(new MockMultipartFile("file", "../bad.txt", "text/plain", new byte[] {65})), teacher, 400);
        check(get(BASE + "/search").param("q", "x"), "Bearer invalid", 401);
        JsonNode answer = data(post(BASE + "/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"量子纠缠\"}"), student);
        assertEquals("no_evidence", answer.path("status").asText());
        assertTrue(answer.path("evidence").isEmpty());
    }

    @Test
    void publicationAndQuestionDoNotCoerceNumericOrStringTypes() throws Exception {
        String id = upload("lesson.txt", TEXT.getBytes(StandardCharsets.UTF_8)).path("id").asText();
        for (String invalid : new String[] {"1", "\"true\"", "null", "{}", "[]"}) {
            check(patch(BASE + "/materials/" + id + "/publication").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"published\":" + invalid + "}"), teacher, 400);
        }
        assertFalse(data(get(BASE + "/materials/" + id), teacher).path("published").asBoolean());
        for (String invalid : new String[] {"123", "true", "null", "{}", "[]"}) {
            check(post(BASE + "/ask").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":" + invalid + "}"), student, 400);
        }
    }

    @Test
    void pinnedDownloadsAndOldManagementActionsCannotUseUnseenNewContent() throws Exception {
        String id = upload("lesson.txt", TEXT.getBytes(StandardCharsets.UTF_8)).path("id").asText();
        publish(id, true);
        mvc.perform(get(BASE + "/materials/" + id + "/file").param("version", "1")
                .header(HttpHeaders.AUTHORIZATION, student))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Material-Version", "1"));
        MockMultipartFile replacement = new MockMultipartFile("file", "lesson.txt", "text/plain",
                "软件测试通过执行程序发现缺陷。".getBytes(StandardCharsets.UTF_8));
        JsonNode updated = data(multipart(BASE + "/materials/" + id).file(replacement).param("version", "1")
                .with(request -> { request.setMethod("PUT"); return request; }), teacher);
        assertEquals(2, updated.path("version").asInt());
        check(patch(BASE + "/materials/" + id + "/publication").contentType(MediaType.APPLICATION_JSON)
                .content("{\"published\":true,\"expectedVersion\":1}"), teacher, 409);
        check(delete(BASE + "/materials/" + id).param("version", "1"), teacher, 409);
        check(post(BASE + "/materials/" + id + "/reprocess").param("version", "1"), teacher, 409);
        check(multipart(BASE + "/materials/" + id).file(replacement).param("version", "1")
                .with(request -> { request.setMethod("PUT"); return request; }), teacher, 409);
        assertFalse(data(get(BASE + "/materials/" + id), teacher).path("published").asBoolean());
        data(patch(BASE + "/materials/" + id + "/publication").contentType(MediaType.APPLICATION_JSON)
                .content("{\"published\":true,\"expectedVersion\":2}"), teacher);
        check(get(BASE + "/materials/" + id + "/file").param("version", "1"), student, 409);
        byte[] downloaded = mvc.perform(get(BASE + "/materials/" + id + "/file").param("version", "2")
                .header(HttpHeaders.AUTHORIZATION, student)).andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(replacement.getBytes(), downloaded);
        check(get(BASE + "/materials/" + id + "/file").param("version", "0"), student, 400);
        check(get(BASE + "/materials/" + id + "/file").param("version", "1"), login("student2"), 404);
        for (String invalid : new String[] {"0", "-1", "1.5", "\"2\"", "true", "null", "2147483648"}) {
            check(patch(BASE + "/materials/" + id + "/publication").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"published\":false,\"expectedVersion\":" + invalid + "}"), teacher, 400);
        }
        assertTrue(data(get(BASE + "/materials/" + id), teacher).path("published").asBoolean());
    }

    @Test
    void capabilitiesRespectCourseScopeAndAllowTimeForTheLongestModelRequest() throws Exception {
        JsonNode capabilities = data(get(BASE + "/capabilities"), student);
        assertFalse(capabilities.path("generationConfigured").asBoolean());
        assertEquals(75, capabilities.path("answerTimeoutSeconds").asInt());
        assertEquals(2, capabilities.size());
        check(get(BASE + "/capabilities"), login("student2"), 404);
        check(get(BASE + "/capabilities"), "Bearer invalid", 401);
    }

    private JsonNode upload(String name, byte[] bytes) throws Exception {
        return data(multipart(BASE + "/materials").file(new MockMultipartFile("file", name, "application/octet-stream", bytes)), teacher);
    }
    private void publish(String id, boolean published) throws Exception {
        data(patch(BASE + "/materials/" + id + "/publication").contentType(MediaType.APPLICATION_JSON)
                .content("{\"published\":" + published + "}"), teacher);
    }
    private JsonNode search() throws Exception { return data(get(BASE + "/search").param("q", "残差连接"), student); }
    private JsonNode data(MockHttpServletRequestBuilder request, String auth) throws Exception {
        return json.readTree(mvc.perform(request.header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.code").value(200)).andReturn().getResponse().getContentAsByteArray()).path("data");
    }
    private void check(MockHttpServletRequestBuilder request, String auth, int code) throws Exception {
        mvc.perform(request.header(HttpHeaders.AUTHORIZATION, auth)).andExpect(jsonPath("$.code").value(code));
    }
    private String login(String username) throws Exception {
        return "Bearer " + json.readTree(mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(java.util.Map.of("username", username, "password", "CampusTest-Password"))))
                .andReturn().getResponse().getContentAsByteArray()).path("token").asText();
    }
}
