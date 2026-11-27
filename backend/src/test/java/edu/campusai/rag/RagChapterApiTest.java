package edu.campusai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:rag_chapters;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "campusai.dev-password=Chapter-Test",
        "campusai.rag.model.api-key=", "campusai.rag.model.base-url=", "campusai.rag.model.name="})
@AutoConfigureMockMvc
class RagChapterApiTest {
    static final String BASE = "/campusai/courses/1/rag";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    String teacher, student, other;

    @BeforeEach void setup() throws Exception {
        jdbc.sql("DELETE FROM rag_material").update();
        teacher=login("teacher"); student=login("student"); other=login("student2");
    }

    @Test void uploadAndChapterFilteringRespectPublicationAndCourseScope() throws Exception {
        JsonNode created=data(multipart(BASE+"/materials").file(file("残差连接把输入与变换后的输出相加，为梯度提供更直接的传播路径。"))
                .param("chapterId","102"), teacher);
        String id=created.path("id").asText();
        assertEquals(102,created.path("chapterId").asLong());
        assertEquals(1,data(get(BASE+"/materials").param("chapterId","102"),teacher).size());
        assertTrue(data(get(BASE+"/materials").param("chapterId","102"),student).isEmpty());
        publish(id,1);
        assertEquals(id,data(get(BASE+"/materials").param("chapterId","102"),student).get(0).path("id").asText());
        assertTrue(data(get(BASE+"/materials").param("chapterId","101"),student).isEmpty());
        check(get(BASE+"/materials").param("chapterId","201"),student,404);
        check(get(BASE+"/materials").param("chapterId","102"),other,404);
        check(get(BASE+"/materials").param("chapterId","0"),student,400);
    }

    @Test void invalidChapterCannotLeaveAnUploadedMaterialAndStudentsCannotAssign() throws Exception {
        for (String chapter:new String[]{"201","999"})
            check(multipart(BASE+"/materials").file(file("资料正文")).param("chapterId",chapter),teacher,404);
        check(multipart(BASE+"/materials").file(file("资料正文")).param("chapterId","102"),student,403);
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM rag_material").query(Integer.class).single());
        String id=upload();
        check(assign(id,"102","1"),student,403);
        check(assign(id,"201","1"),teacher,404);
        assertTrue(data(get(BASE+"/materials/"+id),teacher).path("chapterId").isNull());
    }

    @Test void reassignmentAndClearingKeepContentVersionsAndExistingCitations() throws Exception {
        String id=upload();publish(id,1);
        JsonNode source=data(get(BASE+"/search").param("q","残差连接"),student).path("hits").get(0).path("source");
        String chunk=source.path("chunkId").asText();
        for(String chapter:new String[]{"102","102","101","null"}) {
            JsonNode result=data(assign(id,chapter,"1"),teacher);
            assertEquals(id,result.path("id").asText());assertEquals(1,result.path("version").asInt());
            assertTrue(result.path("published").asBoolean());
            assertEquals(source,data(get(BASE+"/sources/"+chunk),student));
        }
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM rag_material_chapter").query(Integer.class).single());
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM rag_chunk").query(Integer.class).single());
    }

    @Test void replacementKeepsChapterButRejectsStaleAssignmentAndDeletesCascade() throws Exception {
        String id=upload();data(assign(id,"102","1"),teacher);publish(id,1);
        String chunk=data(get(BASE+"/search").param("q","残差连接"),student).path("hits").get(0).path("source").path("chunkId").asText();
        JsonNode result=data(multipart(BASE+"/materials/"+id).file(file("软件测试通过执行程序发现缺陷。"))
                .param("version","1").with(r->{r.setMethod("PUT");return r;}),teacher);
        assertEquals(102,result.path("chapterId").asLong());assertEquals(2,result.path("version").asInt());
        assertFalse(result.path("published").asBoolean());
        check(assign(id,"101","1"),teacher,409);
        check(get(BASE+"/sources/"+chunk),student,404);
        data(assign(id,"101","2"),teacher);
        check(delete(BASE+"/materials/"+id).param("version","2"),teacher,200);
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM rag_material_chapter").query(Integer.class).single());
    }

    @Test void chapterAssignmentRequiresExplicitTypedValuesAndCurrentVersion() throws Exception {
        String id=upload();
        for(String bad:new String[]{"\"102\"","1.5","true","{}","[]","0","-1","9223372036854775808"})
            check(assign(id,bad,"1"),teacher,400);
        for(String bad:new String[]{"null","\"1\"","1.5","false","0","2147483648"})
            check(assign(id,"102",bad),teacher,400);
        check(patch(BASE+"/materials/"+id+"/chapter").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"),teacher,400);
        assertTrue(data(get(BASE+"/materials/"+id),teacher).path("chapterId").isNull());
    }

    @Test void chapterForeignKeyPreventsAccidentallyDeletingUsedChapter() throws Exception {
        String id=upload();data(assign(id,"102","1"),teacher);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                ()->jdbc.sql("DELETE FROM campus_course_chapter WHERE id=102").update());
        assertEquals(102,data(get(BASE+"/materials/"+id),teacher).path("chapterId").asLong());
    }

    String upload() throws Exception {return data(multipart(BASE+"/materials").file(file("残差连接把输入与变换后的输出相加，为梯度提供更直接的传播路径。")),teacher).path("id").asText();}
    MockMultipartFile file(String text){return new MockMultipartFile("file","chapter.txt","text/plain",text.getBytes(StandardCharsets.UTF_8));}
    MockHttpServletRequestBuilder assign(String id,String chapter,String version){return patch(BASE+"/materials/"+id+"/chapter").contentType(MediaType.APPLICATION_JSON)
            .content("{\"chapterId\":"+chapter+",\"expectedVersion\":"+version+"}");}
    void publish(String id,int version) throws Exception {data(patch(BASE+"/materials/"+id+"/publication").contentType(MediaType.APPLICATION_JSON)
            .content("{\"published\":true,\"expectedVersion\":"+version+"}"),teacher);}
    JsonNode data(MockHttpServletRequestBuilder request,String auth)throws Exception{return mapper.readTree(mvc.perform(request.header("Authorization",auth))
            .andExpect(jsonPath("$.code").value(200)).andReturn().getResponse().getContentAsByteArray()).path("data");}
    void check(MockHttpServletRequestBuilder request,String auth,int code)throws Exception{mvc.perform(request.header("Authorization",auth)).andExpect(jsonPath("$.code").value(code));}
    String login(String username)throws Exception{return "Bearer "+mapper.readTree(mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(Map.of("username",username,"password","Chapter-Test")))).andReturn().getResponse().getContentAsByteArray()).path("token").asText();}
}
