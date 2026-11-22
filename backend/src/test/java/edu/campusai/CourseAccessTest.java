package edu.campusai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:campusai_course_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "campusai.dev-password=CampusTest-Password"
})
@AutoConfigureMockMvc
class CourseAccessTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void studentsCanOnlyReadTheirOwnCoursesAndCannotReadAdminMenu() throws Exception {
        mvc.perform(get("/campusai/courses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        String student = login("student");
        mvc.perform(get("/getInfo").header(HttpHeaders.AUTHORIZATION, student))
                .andExpect(jsonPath("$.roles[0]").value("student"));
        mvc.perform(get("/campusai/courses").header(HttpHeaders.AUTHORIZATION, student))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].courseCode").value("CS101"));
        mvc.perform(get("/getRouters").header(HttpHeaders.AUTHORIZATION, student))
                .andExpect(jsonPath("$.code").value(403));
        mvc.perform(get("/campusai/courses/1").header(HttpHeaders.AUTHORIZATION, student))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.courseCode").value("CS101"));
        mvc.perform(get("/campusai/courses/2").header(HttpHeaders.AUTHORIZATION, student))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.data").doesNotExist());

        String otherStudent = login("student2");
        mvc.perform(get("/campusai/courses").header(HttpHeaders.AUTHORIZATION, otherStudent))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].courseCode").value("SE101"));
        mvc.perform(get("/campusai/courses/1").header(HttpHeaders.AUTHORIZATION, otherStudent))
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @Transactional
    void teacherAndAdminCanLoadCoursesAndAdminMenu() throws Exception {
        jdbc.sql("""
                INSERT INTO campus_course (id, course_code, name, description, teacher_username)
                VALUES (3, 'WEB101', 'Web 应用开发', '用于验证教师课程隔离', 'otherTeacher')
                """).update();

        for (String username : new String[] {"teacher", "admin"}) {
            String auth = login(username);
            mvc.perform(get("/getInfo").header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(jsonPath("$.roles[0]").value(username));
            mvc.perform(get("/campusai/courses").header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(jsonPath("$.data.length()").value(username.equals("admin") ? 3 : 2));
            mvc.perform(get("/getRouters").header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(jsonPath("$.data[0].children[0].component").value("campusai/courses/index"));
            mvc.perform(get("/campusai/courses/3").header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(jsonPath("$.code").value(username.equals("admin") ? 200 : 404));
        }
    }

    @Test
    void malformedRequestsAndUnavailableCoursesUseTheCommonEnvelope() throws Exception {
        mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").isString());
        mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"student\",\"password\":\"\"}"))
                .andExpect(jsonPath("$.code").value(400));
        String auth = login("student");
        mvc.perform(get("/campusai/courses/999").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.code").value(404));
        mvc.perform(get("/campusai/courses/invalid").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void invalidPasswordDoesNotIssueTokenAndLogoutRevokesToken() throws Exception {
        mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "student", "password": "wrong"}
                                """))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.token").doesNotExist());

        String auth = login("student");
        mvc.perform(post("/logout").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/campusai/courses").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.code").value(401));
    }

    private String login(String username) throws Exception {
        MvcResult result = mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"CampusTest-Password"}
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return "Bearer " + json.readTree(result.getResponse().getContentAsByteArray()).path("token").asText();
    }
}
