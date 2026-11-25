package edu.campusai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:campusai_office_api;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "campusai.dev-password=CampusTest-Password",
        "campusai.rag.model.base-url=", "campusai.rag.model.name=", "campusai.rag.model.api-key="
})
@AutoConfigureMockMvc
class OfficeRagApiTest {
    private static final String BASE = "/campusai/courses/1/rag";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    String teacher;
    String student;

    @BeforeEach
    void prepare() throws Exception {
        jdbc.sql("DELETE FROM rag_material").update();
        teacher = login("teacher");
        student = login("student");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ppt", "pptx", "xls", "xlsx"})
    void officeUploadPublicationRetrievalAndDownloadKeepStoredLocations(String extension) throws Exception {
        byte[] bytes = OfficeSampleFiles.create(extension);
        JsonNode uploaded = upload("lesson." + extension, bytes);
        String id = uploaded.path("id").asText();
        assertEquals("READY", uploaded.path("parseStatus").asText(), uploaded.toPrettyString());
        assertFalse(uploaded.path("published").asBoolean());
        assertTrue(uploaded.path("chunkCount").asInt() >= 2);
        assertTrue(search().path("hits").isEmpty());
        check(get(BASE + "/materials/" + id + "/file"), student, 404);

        publish(id, true);
        JsonNode source = sourceWithText(search().path("hits"), OfficeSampleFiles.SOURCE_TEXT);
        String chunkId = source.path("chunkId").asText();
        assertEquals(id, source.path("materialId").asText());
        assertEquals(1, source.path("version").asInt());
        assertEquals("lesson." + extension, source.path("filename").asText());
        assertTrue(source.path("pageNumber").isNull());
        assertTrue(source.path("paragraphStart").isNull());
        assertTrue(source.path("paragraphEnd").isNull());
        assertEquals(source, data(get(BASE + "/sources/" + chunkId), student));

        if (extension.startsWith("ppt")) {
            assertEquals(2, source.path("slideNumber").asInt());
            assertTrue(source.path("sheetName").isNull());
            assertTrue(source.path("cellRange").isNull());
            assertEquals(2, jdbc.sql("SELECT slide_number FROM rag_chunk_location WHERE chunk_id=:id")
                    .param("id", chunkId).query(Integer.class).single());
        } else {
            assertTrue(source.path("slideNumber").isNull());
            assertEquals(OfficeSampleFiles.FIRST_SHEET, source.path("sheetName").asText());
            assertEquals("A1:F7", source.path("cellRange").asText());
            assertTrue(source.path("text").asText().contains("B3"));
            assertTrue(source.path("text").asText().contains("25.00%"));
            assertFalse(source.path("text").asText().contains("解析失败的资料不能发布"));
            assertEquals("A1:F7", jdbc.sql("SELECT cell_range FROM rag_chunk_location WHERE chunk_id=:id")
                    .param("id", chunkId).query(String.class).single());
            assertEquals(OfficeSampleFiles.FIRST_SHEET,
                    jdbc.sql("SELECT sheet_name FROM rag_chunk_location WHERE chunk_id=:id")
                            .param("id", chunkId).query(String.class).single());
        }

        JsonNode answer = data(post(BASE + "/ask").contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"引用定位\"}"), student);
        assertEquals("retrieval_only", answer.path("status").asText());
        assertEquals(source, sourceWithText(answer.path("evidence"), OfficeSampleFiles.SOURCE_TEXT));
        assertArrayEquals(bytes, mvc.perform(get(BASE + "/materials/" + id + "/file").param("version", "1")
                .header(HttpHeaders.AUTHORIZATION, student)).andReturn().getResponse().getContentAsByteArray());

        String other = login("student2");
        check(get(BASE + "/sources/" + chunkId), other, 404);
        check(get("/campusai/courses/2/rag/sources/" + chunkId), other, 404);
        check(get(BASE + "/materials/" + id + "/file"), other, 404);
        check(multipart(BASE + "/materials").file(file("lesson." + extension, bytes)), student, 403);
        publish(id, false);
        assertTrue(search().path("hits").isEmpty());
        check(get(BASE + "/sources/" + chunkId), student, 404);
    }

    @Test
    void replacementAndDeletionInvalidateOldOfficeSourcesAndCascadeLocationRows() throws Exception {
        String id = upload("lesson.pptx", OfficeSampleFiles.create("pptx")).path("id").asText();
        publish(id, true);
        String oldChunk = sourceWithText(search().path("hits"), OfficeSampleFiles.SOURCE_TEXT).path("chunkId").asText();
        JsonNode retry = data(post(BASE + "/materials/" + id + "/reprocess"), teacher);
        assertEquals(1, retry.path("version").asInt());
        assertTrue(retry.path("published").asBoolean());
        assertTrue(locationCount() >= 2);

        JsonNode updated = data(multipart(BASE + "/materials/" + id)
                .file(file("lesson.xlsx", OfficeSampleFiles.create("xlsx"))).param("version", "1")
                .with(request -> { request.setMethod("PUT"); return request; }), teacher);
        assertEquals("READY", updated.path("parseStatus").asText());
        assertEquals(2, updated.path("version").asInt());
        assertFalse(updated.path("published").asBoolean());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM rag_chunk_location WHERE chunk_id=:id")
                .param("id", oldChunk).query(Integer.class).single());
        check(get(BASE + "/sources/" + oldChunk), student, 404);
        check(get(BASE + "/materials/" + id + "/file").param("version", "1"), teacher, 409);
        publish(id, true);
        JsonNode newSource = sourceWithText(search().path("hits"), OfficeSampleFiles.SOURCE_TEXT);
        assertEquals(2, newSource.path("version").asInt());
        assertTrue(newSource.path("slideNumber").isNull());
        assertEquals(OfficeSampleFiles.FIRST_SHEET, newSource.path("sheetName").asText());
        assertNotEquals(oldChunk, newSource.path("chunkId").asText());

        check(delete(BASE + "/materials/" + id).param("version", "2"), teacher, 200);
        check(get(BASE + "/sources/" + newSource.path("chunkId").asText()), student, 404);
        assertEquals(0, locationCount());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM rag_chunk").query(Integer.class).single());
    }

    @Test
    void failedOfficeReplacementCannotLeavePublishedOrOrphanedLocations() throws Exception {
        String id = upload("lesson.xls", OfficeSampleFiles.create("xls")).path("id").asText();
        publish(id, true);
        JsonNode result = data(multipart(BASE + "/materials/" + id)
                .file(file("broken.pptx", "invalid file".getBytes(StandardCharsets.UTF_8)))
                .with(request -> { request.setMethod("PUT"); return request; }), teacher);
        assertEquals("FAILED", result.path("parseStatus").asText());
        assertEquals(0, result.path("chunkCount").asInt());
        assertFalse(result.path("published").asBoolean());
        assertFalse(result.path("errorMessage").asText().isBlank());
        assertEquals(0, locationCount());
        assertTrue(search().path("hits").isEmpty());
        check(patch(BASE + "/materials/" + id + "/publication").contentType(MediaType.APPLICATION_JSON)
                .content("{\"published\":true}"), teacher, 409);
    }

    @Test
    void existingTextSourceWithoutLocationRowKeepsItsParagraphContract() throws Exception {
        String id = upload("lesson.txt", ("\n" + OfficeSampleFiles.SOURCE_TEXT).getBytes(StandardCharsets.UTF_8))
                .path("id").asText();
        // This is the persisted shape of pre-Office sources after the additive schema upgrade.
        jdbc.sql("DELETE FROM rag_chunk_location").update();
        publish(id, true);
        JsonNode source = sourceWithText(search().path("hits"), OfficeSampleFiles.SOURCE_TEXT);
        assertEquals(2, source.path("paragraphStart").asInt());
        assertEquals(2, source.path("paragraphEnd").asInt());
        assertTrue(source.path("pageNumber").isNull());
        assertTrue(source.path("slideNumber").isNull());
        assertTrue(source.path("sheetName").isNull());
        assertTrue(source.path("cellRange").isNull());
        assertEquals(source, data(get(BASE + "/sources/" + source.path("chunkId").asText()), student));
    }

    private int locationCount() {
        return jdbc.sql("SELECT COUNT(*) FROM rag_chunk_location").query(Integer.class).single();
    }

    private JsonNode sourceWithText(JsonNode hits, String text) {
        for (JsonNode hit : hits) {
            if (hit.path("source").path("text").asText().contains(text)) return hit.path("source");
        }
        fail("No source contains expected text: " + hits.toPrettyString());
        return null;
    }

    private MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/octet-stream", bytes);
    }

    private JsonNode upload(String name, byte[] bytes) throws Exception {
        return data(multipart(BASE + "/materials").file(file(name, bytes)), teacher);
    }

    private JsonNode search() throws Exception {
        return data(get(BASE + "/search").param("q", "引用定位"), student);
    }

    private void publish(String id, boolean published) throws Exception {
        data(patch(BASE + "/materials/" + id + "/publication").contentType(MediaType.APPLICATION_JSON)
                .content("{\"published\":" + published + "}"), teacher);
    }

    private JsonNode data(MockHttpServletRequestBuilder request, String auth) throws Exception {
        return json.readTree(mvc.perform(request.header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.code").value(200)).andReturn().getResponse().getContentAsByteArray()).path("data");
    }

    private void check(MockHttpServletRequestBuilder request, String auth, int code) throws Exception {
        mvc.perform(request.header(HttpHeaders.AUTHORIZATION, auth)).andExpect(jsonPath("$.code").value(code));
    }

    private String login(String username) throws Exception {
        return "Bearer " + json.readTree(mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("username", username, "password", "CampusTest-Password"))))
                .andExpect(jsonPath("$.code").value(200)).andReturn().getResponse().getContentAsByteArray())
                .path("token").asText();
    }
}
