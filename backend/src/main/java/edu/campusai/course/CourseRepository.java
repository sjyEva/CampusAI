package edu.campusai.course;

import java.util.List;
import java.util.Optional;

import edu.campusai.auth.DemoAccounts.Account;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CourseRepository {
    private static final String SELECT = "SELECT c.id, c.course_code, c.name, c.description FROM campus_course c";
    private static final String ORDER_BY = " ORDER BY c.id";

    private final JdbcClient jdbc;

    public CourseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<CourseSummary> findVisible(Account account) {
        return visibleQuery(account, ORDER_BY).query(CourseSummary.class).list();
    }

    public Optional<CourseSummary> findVisibleById(Account account, long courseId) {
        return visibleQuery(account, " AND c.id = :courseId")
                .param("courseId", courseId)
                .query(CourseSummary.class)
                .optional();
    }

    private JdbcClient.StatementSpec visibleQuery(Account account, String suffix) {
        String scope = switch (account.role()) {
            case "admin" -> "1 = 1";
            case "teacher" -> "c.teacher_username = :username";
            case "student" -> """
                    EXISTS (SELECT 1 FROM campus_course_enrollment e
                            WHERE e.course_id = c.id AND e.student_username = :username)
                    """;
            default -> throw new IllegalStateException("Unexpected campus role " + account.role());
        };
        return jdbc.sql(SELECT + " WHERE (" + scope + ")" + suffix)
                .param("username", account.userName());
    }

    public record CourseSummary(long id, String courseCode, String name, String description) {
    }
}
