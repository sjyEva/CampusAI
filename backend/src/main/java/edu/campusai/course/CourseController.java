package edu.campusai.course;

import edu.campusai.auth.DemoAccounts;
import edu.campusai.web.ApiResult;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CourseController {
    private final CourseRepository courses;
    private final DemoAccounts accounts;

    public CourseController(CourseRepository courses, DemoAccounts accounts) {
        this.courses = courses;
        this.accounts = accounts;
    }

    @GetMapping("/campusai/courses")
    public ApiResult list(Authentication principal) {
        DemoAccounts.Account account = accounts.find(principal.getName());
        return ApiResult.success().with("data", courses.findVisible(account));
    }

    @GetMapping("/campusai/courses/{id}")
    public ApiResult detail(Authentication principal, @PathVariable long id) {
        DemoAccounts.Account account = accounts.find(principal.getName());
        return courses.findVisibleById(account, id)
                .map(course -> ApiResult.success().with("data", course))
                .orElseGet(() -> ApiResult.error(404, "课程不存在或无访问权限"));
    }

    @GetMapping("/campusai/courses/{id}/chapters")
    public ApiResult chapters(Authentication principal, @PathVariable long id) {
        DemoAccounts.Account account = accounts.find(principal.getName());
        return courses.findVisibleChapters(account, id)
                .map(chapters -> ApiResult.success().with("data", chapters))
                .orElseGet(() -> ApiResult.error(404, "课程不存在或无访问权限"));
    }
}
