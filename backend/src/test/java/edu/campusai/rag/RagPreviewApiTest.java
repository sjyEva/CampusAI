package edu.campusai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:campusai_rag_preview;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "campusai.dev-password=CampusTest-Password",
        "campusai.rag.model.base-url=", "campusai.rag.model.name=", "campusai.rag.model.api-key="
})
@AutoConfigureMockMvc
@Import(RagPreviewApiTest.ReadBoundaryConfiguration.class)
class RagPreviewApiTest {
    private static final String BASE = "/campusai/courses/1/rag";
    private static final String TEXT = "课程资料预览保留原文和引用位置。";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired ReadBoundaryRepository previews;
    @Autowired RagRepository repository;
    String teacher;
    String student;

    @BeforeEach
    void prepare() throws Exception {
        previews.afterRead(null);
        jdbc.sql("DELETE FROM rag_material").update();
        teacher = login("teacher");
        student = login("student");
    }

    @Test
    void defaultPaginationReturnsStoredChunksInOrdinalOrderAndEndsWithNull() throws Exception {
        List<String> paragraphs = paragraphs(25);
        String id = publishedText(String.join("\n", paragraphs));
        JsonNode first = preview(get(path(id)).param("version", "1"), student);
        assertEquals(2, first.size());
        assertEquals(12, first.path("sources").size());
        assertEquals(12, first.path("nextOffset").asInt());
        JsonNode second = preview(request(id, 1, 12, 12), student);
        assertEquals(12, second.path("sources").size());
        assertEquals(24, second.path("nextOffset").asInt());
        JsonNode last = preview(request(id, 1, 24, 12), student);
        assertEquals(1, last.path("sources").size());
        assertTrue(last.path("nextOffset").isNull());

        List<String> actualTexts = new ArrayList<>();
        for (JsonNode page : List.of(first, second, last)) {
            for (JsonNode source : page.path("sources")) {
                actualTexts.add(source.path("text").asText());
                assertEquals(id, source.path("materialId").asText());
                assertEquals(1, source.path("courseId").asLong());
                assertEquals(1, source.path("version").asInt());
                assertEquals(source, data(get(BASE + "/sources/" + source.path("chunkId").asText()), student));
            }
        }
        assertEquals(paragraphs, actualTexts);
        assertEmpty(preview(request(id, 1, 25, 12), student));
        assertEmpty(preview(request(id, 1, Integer.MAX_VALUE, 30), student));
        JsonNode whole = preview(request(id, 1, 0, 30), student);
        assertEquals(25, whole.path("sources").size());
        assertTrue(whole.path("nextOffset").isNull());
    }

    @Test
    void exactPageLengthDoesNotAdvertiseAnEmptyNextPage() throws Exception {
        String id = publishedText(String.join("\n", paragraphs(2)));
        JsonNode page = preview(request(id, 1, 0, 2), student);
        assertEquals(2, page.path("sources").size());
        assertTrue(page.path("nextOffset").isNull());
    }

    @Test
    void cursorUsesRealOrdinalsAcrossGapsAndNeverOverflows() throws Exception {
        List<String> paragraphs = paragraphs(2);
        String id = publishedText(String.join("\n", paragraphs));
        jdbc.sql("UPDATE rag_chunk SET ordinal=:ordinal WHERE material_id=:id AND ordinal=1")
                .param("ordinal", Integer.MAX_VALUE - 1).param("id", id).update();
        jdbc.sql("UPDATE rag_chunk SET ordinal=:ordinal WHERE material_id=:id AND ordinal=2")
                .param("ordinal", Integer.MAX_VALUE).param("id", id).update();
        JsonNode first = preview(request(id, 1, 0, 1), student);
        assertEquals(paragraphs.get(0), first.path("sources").get(0).path("text").asText());
        assertEquals(Integer.MAX_VALUE - 1, first.path("nextOffset").asInt());
        JsonNode last = preview(request(id, 1, first.path("nextOffset").asInt(), 1), student);
        assertEquals(paragraphs.get(1), last.path("sources").get(0).path("text").asText());
        assertTrue(last.path("nextOffset").isNull());
        assertEmpty(preview(request(id, 1, Integer.MAX_VALUE, 1), student));
    }

    @Test
    void replacementUnpublicationAndDeletionInvalidatePinnedPreviews() throws Exception {
        String id = upload("lesson.txt", TEXT.getBytes(StandardCharsets.UTF_8)).path("id").asText();
        check(request(id, 1, 0, 12), teacher, 404);
        check(request(id, 1, 0, 12), student, 404);
        publish(id, true);
        assertEquals(TEXT, preview(request(id, 1, 0, 12), student).path("sources").get(0).path("text").asText());

        String replacement = "替换后的课程正文，只能通过新版本预览。";
        data(multipart(BASE + "/materials/" + id).file(file("new.txt", replacement.getBytes(StandardCharsets.UTF_8)))
                .param("version", "1").with(request -> { request.setMethod("PUT"); return request; }), teacher);
        check(request(id, 1, 0, 12), student, 404);
        publish(id, true);
        check(request(id, 1, 0, 12), student, 409);
        check(request(id, 1, 0, 12), teacher, 409);
        assertEquals(replacement, preview(request(id, 2, 0, 12), student).path("sources").get(0).path("text").asText());
        publish(id, false);
        check(request(id, 2, 0, 12), student, 404);
        check(request(id, 2, 0, 12), teacher, 404);
        publish(id, true);
        data(delete(BASE + "/materials/" + id).param("version", "2"), teacher);
        check(request(id, 2, 0, 12), student, 404);
    }

    @Test
    void previewRejectsCrossCourseAccessAndUnauthenticatedRequests() throws Exception {
        String id = publishedText(TEXT);
        String otherStudent = login("student2");
        check(request(id, 1, 0, 12), otherStudent, 404);
        check(get("/campusai/courses/2/rag/materials/" + id + "/preview").param("version", "1"), otherStudent, 404);
        check(get("/campusai/courses/2/rag/materials/" + id + "/preview").param("version", "1"), login("admin"), 404);
        check(request("missing-material", 1, 0, 12), student, 404);
        check(request(id, 1, 0, 12), "Bearer invalid", 401);
        mvc.perform(request(id, 1, 0, 12)).andExpect(jsonPath("$.code").value(401));
        assertEquals(1, preview(request(id, 1, 0, 12), teacher).path("sources").size());
    }

    @Test
    void parametersAreRequiredBoundedAndRemainInTheSharedErrorEnvelope() throws Exception {
        String id = publishedText(TEXT);
        check(get(path(id)), student, 400);
        for (String version : List.of("", "0", "-1", "1.5", "true", "2147483648")) {
            check(get(path(id)).param("version", version), student, 400);
        }
        for (String offset : List.of("-1", "1.5", "true", "2147483648")) {
            check(get(path(id)).param("version", "1").param("offset", offset), student, 400);
        }
        for (String limit : List.of("0", "-1", "31", "1.5", "true", "2147483648")) {
            check(get(path(id)).param("version", "1").param("limit", limit), student, 400);
        }
        assertEquals(1, preview(request(id, 1, 0, 1), student).path("sources").size());
        assertEquals(1, preview(request(id, 1, 0, 30), student).path("sources").size());
        check(request(id, Integer.MAX_VALUE, 0, 12), student, 409);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCESSING", "FAILED"})
    void nonReadyMaterialsCannotBePreviewedEvenWithPublishedFlag(String status) throws Exception {
        String id = publishedText(TEXT);
        jdbc.sql("UPDATE rag_material SET parse_status=:status WHERE id=:id")
                .param("status", status).param("id", id).update();
        check(request(id, 1, 0, 12), student, 404);
        check(request(id, 1, 0, 12), teacher, 404);
        assertTrue(previews.page(1, id, 1, 0, 13).isEmpty());
    }

    @Test
    void repositoryQueryScopesMaterialCourseVersionAndCurrentPublicationTogether() throws Exception {
        String id = publishedText(TEXT);
        String unrelated = publishedText("另一份资料，不能混入目标资料预览。");
        repository.insertChunk("old-version-chunk", id, 0, 99,
                new DocumentParser.ParsedChunk("旧版本正文不应被预览。", null, 1, 1, null));
        List<RagPreviewRepository.PreviewChunk> rows = previews.page(1, id, 1, 0, 31);
        assertEquals(1, rows.size());
        assertEquals(id, rows.get(0).source().materialId());
        assertNotEquals(unrelated, rows.get(0).source().materialId());
        assertEquals(TEXT, rows.get(0).source().text());
        assertTrue(previews.page(2, id, 1, 0, 31).isEmpty());
        assertTrue(previews.page(1, id, 0, 0, 31).isEmpty());
        assertTrue(previews.page(1, "missing-material", 1, 0, 31).isEmpty());
        publish(id, false);
        assertTrue(previews.page(1, id, 1, 0, 31).isEmpty());
        publish(id, true);
        jdbc.sql("UPDATE rag_material SET version=2 WHERE id=:id").param("id", id).update();
        assertTrue(previews.page(1, id, 1, 0, 31).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unpublish", "replace", "delete", "revoke", "not-ready"})
    void committedChangesAfterReadingPreventReturningBufferedText(String change) throws Exception {
        String id = publishedText(TEXT);
        previews.afterRead(() -> {
            switch (change) {
                case "unpublish" -> jdbc.sql("UPDATE rag_material SET published=FALSE WHERE id=:id").param("id", id).update();
                case "replace" -> jdbc.sql("UPDATE rag_material SET version=2 WHERE id=:id").param("id", id).update();
                case "delete" -> jdbc.sql("DELETE FROM rag_material WHERE id=:id").param("id", id).update();
                case "revoke" -> jdbc.sql("DELETE FROM campus_course_enrollment WHERE course_id=1 AND student_username='student'").update();
                case "not-ready" -> jdbc.sql("UPDATE rag_material SET parse_status='PROCESSING' WHERE id=:id").param("id", id).update();
                default -> throw new AssertionError(change);
            }
        });
        try {
            JsonNode response = check(request(id, 1, 0, 12), student, change.equals("replace") ? 409 : 404);
            assertFalse(response.has("data"));
            assertFalse(response.toString().contains(TEXT));
        } finally {
            if (change.equals("revoke")) {
                jdbc.sql("""
                        INSERT INTO campus_course_enrollment(course_id,student_username)
                        SELECT 1,'student' WHERE NOT EXISTS (
                            SELECT 1 FROM campus_course_enrollment WHERE course_id=1 AND student_username='student')
                        """).update();
            }
        }
    }

    @Test
    void textPdfAndDocxPreviewsKeepTheirOriginalPositionFields() throws Exception {
        String textId = publishedText("\n\n" + TEXT + "\n\n末尾段落保留物理行号。");
        JsonNode txt = preview(request(textId, 1, 0, 12), student).path("sources").get(0);
        assertEquals(3, txt.path("paragraphStart").asInt());
        assertEquals(5, txt.path("paragraphEnd").asInt());
        assertNullFields(txt, "pageNumber", "heading", "slideNumber", "sheetName", "cellRange");

        String pdfId = upload("pages.pdf", pdf()).path("id").asText();
        publish(pdfId, true);
        JsonNode pages = preview(request(pdfId, 1, 0, 12), student).path("sources");
        assertEquals(2, pages.size());
        for (int index = 0; index < pages.size(); index++) {
            JsonNode source = pages.get(index);
            assertEquals(index + 1, source.path("pageNumber").asInt());
            assertTrue(source.path("text").asText().contains("Preview page " + (index + 1)));
            assertNullFields(source, "paragraphStart", "paragraphEnd", "heading", "slideNumber", "sheetName", "cellRange");
        }

        String docxId = upload("notes.docx", docx()).path("id").asText();
        publish(docxId, true);
        JsonNode docx = preview(request(docxId, 1, 0, 12), student).path("sources").get(0);
        assertEquals("第一章 课程资料", docx.path("heading").asText());
        assertEquals(1, docx.path("paragraphStart").asInt());
        assertEquals(3, docx.path("paragraphEnd").asInt());
        assertTrue(docx.path("text").asText().contains(TEXT));
        assertNullFields(docx, "pageNumber", "slideNumber", "sheetName", "cellRange");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ppt", "pptx", "xls", "xlsx"})
    void presentationAndSpreadsheetPreviewsKeepLocationExtensionFields(String extension) throws Exception {
        String id = upload("lesson." + extension, OfficeSampleFiles.create(extension)).path("id").asText();
        publish(id, true);
        JsonNode sources = preview(request(id, 1, 0, 30), student).path("sources");
        assertTrue(sources.size() >= 2);
        JsonNode target = null;
        for (JsonNode source : sources) {
            assertEquals(source, data(get(BASE + "/sources/" + source.path("chunkId").asText()), student));
            if (source.path("text").asText().contains(OfficeSampleFiles.SOURCE_TEXT)) target = source;
        }
        assertNotNull(target, sources.toPrettyString());
        assertNullFields(target, "pageNumber", "paragraphStart", "paragraphEnd");
        if (extension.startsWith("ppt")) {
            assertEquals(2, target.path("slideNumber").asInt());
            assertNullFields(target, "sheetName", "cellRange");
        } else {
            assertEquals(OfficeSampleFiles.FIRST_SHEET, target.path("sheetName").asText());
            assertEquals("A1:F7", target.path("cellRange").asText());
            assertNullFields(target, "slideNumber");
        }
    }

    private static List<String> paragraphs(int count) {
        List<String> result = new ArrayList<>();
        for (int index = 1; index <= count; index++) result.add("Preview block %02d ".formatted(index) + "x".repeat(600));
        return result;
    }

    private static String path(String id) { return BASE + "/materials/" + id + "/preview"; }

    private static MockHttpServletRequestBuilder request(String id, int version, int offset, int limit) {
        return get(path(id)).param("version", Integer.toString(version))
                .param("offset", Integer.toString(offset)).param("limit", Integer.toString(limit));
    }

    private JsonNode preview(MockHttpServletRequestBuilder request, String auth) throws Exception {
        return json.readTree(mvc.perform(request.header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.code").value(200)).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsByteArray()).path("data");
    }

    private void assertEmpty(JsonNode page) {
        assertTrue(page.path("sources").isArray());
        assertTrue(page.path("sources").isEmpty());
        assertTrue(page.path("nextOffset").isNull());
    }

    private void assertNullFields(JsonNode source, String... names) {
        for (String name : names) assertTrue(source.has(name) && source.path(name).isNull(), name + ": " + source);
    }

    private String publishedText(String content) throws Exception {
        String id = upload("lesson.txt", content.getBytes(StandardCharsets.UTF_8)).path("id").asText();
        publish(id, true);
        return id;
    }

    private MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/octet-stream", bytes);
    }

    private JsonNode upload(String name, byte[] bytes) throws Exception {
        JsonNode material = data(multipart(BASE + "/materials").file(file(name, bytes)), teacher);
        assertEquals("READY", material.path("parseStatus").asText(), material.toPrettyString());
        return material;
    }

    private void publish(String id, boolean published) throws Exception {
        data(patch(BASE + "/materials/" + id + "/publication").contentType(MediaType.APPLICATION_JSON)
                .content("{\"published\":" + published + "}"), teacher);
    }

    private JsonNode data(MockHttpServletRequestBuilder request, String auth) throws Exception {
        return check(request, auth, 200).path("data");
    }

    private JsonNode check(MockHttpServletRequestBuilder request, String auth, int code) throws Exception {
        return json.readTree(mvc.perform(request.header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.code").value(code)).andReturn().getResponse().getContentAsByteArray());
    }

    private String login(String username) throws Exception {
        return "Bearer " + json.readTree(mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("username", username, "password", "CampusTest-Password"))))
                .andExpect(jsonPath("$.code").value(200)).andReturn().getResponse().getContentAsByteArray())
                .path("token").asText();
    }

    private byte[] pdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            for (int number = 1; number <= 2; number++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(40, 700);
                    content.showText("Preview page " + number);
                    content.endText();
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] docx() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("第一章 课程资料");
            document.createParagraph();
            document.createParagraph().createRun().setText(TEXT);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.write(output);
            return output.toByteArray();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ReadBoundaryConfiguration {
        @Bean
        @Primary
        ReadBoundaryRepository readBoundaryRepository(JdbcClient jdbc) { return new ReadBoundaryRepository(jdbc); }
    }

    /** Deterministic committed mutation at the read boundary, with the real SQL and service. */
    static class ReadBoundaryRepository extends RagPreviewRepository {
        private Runnable afterRead;

        ReadBoundaryRepository(JdbcClient jdbc) { super(jdbc); }

        // Use a method so Spring's repository proxy updates the actual target instance.
        public void afterRead(Runnable action) { afterRead = action; }

        @Override
        public List<PreviewChunk> page(long courseId, String materialId, int version, int offset, int rowLimit) {
            List<PreviewChunk> result = super.page(courseId, materialId, version, offset, rowLimit);
            Runnable change = afterRead;
            afterRead = null;
            if (change != null) change.run();
            return result;
        }
    }
}
