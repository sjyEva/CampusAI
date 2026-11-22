package edu.campusai.auth;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DemoAccounts implements UserDetailsService {
    private static final List<Account> USERS = List.of(
            new Account(1, "admin", "系统管理员", "admin"),
            new Account(2, "teacher", "演示教师", "teacher"),
            new Account(3, "student", "演示学生", "student"),
            new Account(4, "student2", "第二位演示学生", "student")
    );

    private final Map<String, Account> accounts = USERS.stream()
            .collect(Collectors.toUnmodifiableMap(Account::userName, Function.identity()));

    private final String passwordHash;

    public DemoAccounts(PasswordEncoder encoder, @Value("${campusai.dev-password}") String password) {
        this.passwordHash = encoder.encode(password);
    }

    public Account find(String username) {
        Account account = accounts.get(username);
        if (account == null) {
            throw new UsernameNotFoundException("账号不存在");
        }
        return account;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        Account account = find(username);
        return User.withUsername(account.userName())
                .password(passwordHash)
                .roles(account.role())
                .build();
    }

    public record Account(long userId, String userName, String nickName, String role) {
    }
}
