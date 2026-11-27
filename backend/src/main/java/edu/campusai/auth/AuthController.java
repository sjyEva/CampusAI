package edu.campusai.auth;

import java.util.List;
import java.util.Map;

import edu.campusai.web.ApiResult;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

// The login, profile and route response shape follows RuoYi-Vue's SysLoginController.
@RestController
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SessionTokens tokens;
    private final DemoAccounts accounts;

    public AuthController(AuthenticationManager authenticationManager, SessionTokens tokens, DemoAccounts accounts) {
        this.authenticationManager = authenticationManager;
        this.tokens = tokens;
        this.accounts = accounts;
    }

    @PostMapping("/login")
    public ApiResult login(@RequestBody LoginRequest request) {
        if (request.username() == null || request.username().isBlank()
                || request.password() == null || request.password().isBlank()) {
            return ApiResult.error(400, "请输入账号和密码");
        }
        try {
            Authentication result = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
            return ApiResult.success().with("token", tokens.issue(result));
        } catch (BadCredentialsException error) {
            return ApiResult.error(401, "账号或密码错误");
        }
    }

    @GetMapping("/captchaImage")
    public ApiResult captcha() {
        return ApiResult.success().with("captchaEnabled", false);
    }

    @GetMapping("/getInfo")
    public ApiResult getInfo(Authentication principal) {
        DemoAccounts.Account account = accounts.find(principal.getName());
        String permission = account.role().equals("admin") ? "*:*:*" : "campusai:courses:list";
        return ApiResult.success()
                .with("user", Map.of("userId", account.userId(), "userName", account.userName(),
                        "nickName", account.nickName(), "avatar", ""))
                .with("roles", List.of(account.role()))
                .with("permissions", List.of(permission));
    }

    @GetMapping("/getRouters")
    public ApiResult getRouters() {
        var page = Map.of("path", "", "name", "CampusAiCoursesIndex", "component", "campusai/courses/index",
                "meta", Map.of("title", "校园课程", "icon", "education"));
        var menu = Map.of("path", "/campusai-courses", "name", "CampusAiCourses", "component", "Layout",
                "children", List.of(page));
        return ApiResult.success().with("data", List.of(menu));
    }

    @PostMapping("/logout")
    public ApiResult logout(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        tokens.revoke(authorization);
        return ApiResult.success();
    }

    public record LoginRequest(String username, String password) {
    }
}
