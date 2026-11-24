package edu.campusai.rag;

import edu.campusai.auth.DemoAccounts.Account;
import edu.campusai.course.CourseRepository;
import org.springframework.stereotype.Component;

@Component
public class RagAccess {
    private final CourseRepository courses;

    public RagAccess(CourseRepository courses) { this.courses = courses; }

    public void read(Account account, long courseId) {
        if (courses.findVisibleById(account, courseId).isEmpty()) {
            throw new RagException(404, "课程不存在或无访问权限");
        }
    }

    public void manage(Account account, long courseId) {
        if (!account.role().equals("teacher") && !account.role().equals("admin")) {
            throw new RagException(403, "只有负责本课程的教师或管理员可以管理资料");
        }
        read(account, courseId);
    }
}
