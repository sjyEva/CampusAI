package edu.campusai.auth;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
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

    private final Map<String, String> passwordHashes;

    public DemoAccounts(PasswordEncoder encoder, String password) {
        this(encoder, password, Map.of(), false);
    }

    @Autowired
    public DemoAccounts(PasswordEncoder encoder, @Value("${campusai.dev-password}") String password,
                        Environment environment) {
        this(encoder, password, configuredPasswords(environment),
                environment.getProperty("campusai.public-demo", Boolean.class, false));
    }

    private DemoAccounts(PasswordEncoder encoder, String password,
                         Map<String, String> configuredPasswords, boolean publicDemo) {
        if (publicDemo) {
            validatePublicPasswords(configuredPasswords, password);
        }
        this.passwordHashes = USERS.stream().collect(Collectors.toUnmodifiableMap(
                Account::userName,
                account -> encoder.encode(configuredPasswords.getOrDefault(account.userName(), password))));
    }

    private static Map<String, String> configuredPasswords(Environment environment) {
        Map<String, String> passwords = new LinkedHashMap<>();
        for (Account account : USERS) {
            String password = environment.getProperty("campusai.demo-passwords." + account.userName());
            if (password != null && !password.isBlank()) {
                passwords.put(account.userName(), password);
            }
        }
        return passwords;
    }

    private static void validatePublicPasswords(Map<String, String> passwords, String devPassword) {
        Set<String> distinctPasswords = new HashSet<>();
        for (Account account : USERS) {
            String property = "campusai.demo-passwords." + account.userName();
            String password = passwords.get(account.userName());
            if (password == null || password.length() < 16) {
                throw new IllegalStateException("Public demo requires " + property
                        + " to be explicitly configured with at least 16 characters.");
            }
            if (password.equals(devPassword)) {
                throw new IllegalStateException("Public demo requires " + property
                        + " to differ from campusai.dev-password.");
            }
            if (!distinctPasswords.add(password)) {
                throw new IllegalStateException("Public demo requires a distinct password for each demo account.");
            }
        }
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
                .password(passwordHashes.get(account.userName()))
                .roles(account.role())
                .build();
    }

    public record Account(long userId, String userName, String nickName, String role) {
    }
}
