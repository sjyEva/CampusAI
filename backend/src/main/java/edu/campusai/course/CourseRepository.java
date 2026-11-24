package edu.campusai.course;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import edu.campusai.auth.DemoAccounts.Account;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CourseRepository {
    private static final String COURSE_SELECT = "SELECT c.id, c.course_code, c.name, c.description FROM campus_course c";
    private static final String CHAPTER_SELECT = """
            SELECT ch.id, ch.title, ch.sort_order FROM campus_course c
            LEFT JOIN campus_course_chapter ch ON ch.course_id = c.id
            """;
    private static final String ORDER_BY = " ORDER BY c.id";

    private final JdbcClient jdbc;

    public CourseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<CourseSummary> findVisible(Account account) {
        return visibleQuery(account, COURSE_SELECT, ORDER_BY).query(CourseSummary.class).list();
    }

    public Optional<CourseSummary> findVisibleById(Account account, long courseId) {
        return visibleQuery(account, COURSE_SELECT, " AND c.id = :courseId")
                .param("courseId", courseId)
                .query(CourseSummary.class)
                .optional();
    }

    public Optional<List<ChapterSummary>> findVisibleChapters(Account account, long courseId) {
        return visibleQuery(account, CHAPTER_SELECT, " AND c.id = :courseId ORDER BY ch.sort_order, ch.id")
                .param("courseId", courseId)
                .query(rows -> {
                    // The LEFT JOIN yields one row with a null chapter ID for a visible, empty course.
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    List<ChapterSummary> chapters = new ArrayList<>();
                    do {
                        long id = rows.getLong("id");
                        if (!rows.wasNull()) {
                            chapters.add(new ChapterSummary(id, rows.getString("title"), rows.getInt("sort_order")));
                        }
                    } while (rows.next());
                    return Optional.of(chapters);
                });
    }

    private JdbcClient.StatementSpec visibleQuery(Account account, String select, String suffix) {
        String scope = switch (account.role()) {
            case "admin" -> "1 = 1";
            case "teacher" -> "c.teacher_username = :username";
            case "student" -> """
                    EXISTS (SELECT 1 FROM campus_course_enrollment e
                            WHERE e.course_id = c.id AND e.student_username = :username)
                    """;
            default -> throw new IllegalStateException("Unexpected campus role " + account.role());
        };
        return jdbc.sql(select + " WHERE (" + scope + ")" + suffix)
                .param("username", account.userName());
    }

    public record CourseSummary(long id, String courseCode, String name, String description) {
    }

    public record ChapterSummary(long id, String title, int sortOrder) {
    }
}
