package edu.campusai.auth;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoAccountsTest {
    private static final String DEV_PASSWORD = "Development-Only-Password";
    private static final Map<String, String> PASSWORDS = Map.of(
            "admin", "Admin-Unique-Password-1",
            "teacher", "Teacher-Unique-Password-2",
            "student", "Student-Unique-Password-3",
            "student2", "Student2-Unique-Password-4");

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

    @Test
    void independentPasswordsOnlyMatchTheirOwnAccount() {
        DemoAccounts accounts = new DemoAccounts(encoder, DEV_PASSWORD, publicEnvironment());

        PASSWORDS.forEach((username, ownPassword) -> {
            String encoded = accounts.loadUserByUsername(username).getPassword();
            assertThat(encoder.matches(ownPassword, encoded)).isTrue();
            assertThat(encoder.matches(DEV_PASSWORD, encoded)).isFalse();
            PASSWORDS.forEach((otherUsername, otherPassword) -> {
                if (!otherUsername.equals(username)) {
                    assertThat(encoder.matches(otherPassword, encoded)).isFalse();
                }
            });
        });
    }

    @Test
    void defaultDevelopmentModeAndOriginalConstructorKeepSharedPasswordCompatibility() {
        DemoAccounts environmentAccounts = new DemoAccounts(encoder, DEV_PASSWORD, new MockEnvironment());
        DemoAccounts originalConstructorAccounts = new DemoAccounts(encoder, DEV_PASSWORD);

        for (String username : PASSWORDS.keySet()) {
            assertThat(encoder.matches(DEV_PASSWORD,
                    environmentAccounts.loadUserByUsername(username).getPassword())).isTrue();
            assertThat(encoder.matches(DEV_PASSWORD,
                    originalConstructorAccounts.loadUserByUsername(username).getPassword())).isTrue();
        }
    }

    @Test
    void developmentModeSupportsOneOverrideWhileOtherAccountsUseFallback() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("campusai.demo-passwords.teacher", PASSWORDS.get("teacher"));
        DemoAccounts accounts = new DemoAccounts(encoder, DEV_PASSWORD, environment);

        assertThat(encoder.matches(PASSWORDS.get("teacher"),
                accounts.loadUserByUsername("teacher").getPassword())).isTrue();
        assertThat(encoder.matches(DEV_PASSWORD,
                accounts.loadUserByUsername("teacher").getPassword())).isFalse();
        for (String username : new String[] {"admin", "student", "student2"}) {
            assertThat(encoder.matches(DEV_PASSWORD,
                    accounts.loadUserByUsername(username).getPassword())).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "teacher", "student", "student2"})
    void publicDemoRejectsEachMissingAccountPassword(String username) {
        MockEnvironment environment = new MockEnvironment().withProperty("campusai.public-demo", "true");
        PASSWORDS.forEach((account, password) -> {
            if (!account.equals(username)) {
                environment.setProperty("campusai.demo-passwords." + account, password);
            }
        });

        assertThatThrownBy(() -> new DemoAccounts(encoder, DEV_PASSWORD, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("campusai.demo-passwords." + username)
                .hasMessageNotContaining(DEV_PASSWORD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "                ", "weak-secret", "123456789012345"})
    void publicDemoRejectsBlankOrShortPasswords(String weakPassword) {
        MockEnvironment environment = publicEnvironment()
                .withProperty("campusai.demo-passwords.student2", weakPassword);

        assertThatThrownBy(() -> new DemoAccounts(encoder, DEV_PASSWORD, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("campusai.demo-passwords.student2")
                .hasMessageContaining("at least 16 characters")
                .hasMessageNotContaining("weak-secret")
                .hasMessageNotContaining("123456789012345");
    }

    @Test
    void publicDemoRejectsDuplicatePasswordsWithoutIncludingThemInError() {
        MockEnvironment environment = publicEnvironment()
                .withProperty("campusai.demo-passwords.student2", PASSWORDS.get("student"));

        assertThatThrownBy(() -> new DemoAccounts(encoder, DEV_PASSWORD, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distinct password")
                .hasMessageNotContaining(PASSWORDS.get("student"));
    }

    @Test
    void publicDemoRejectsReusingDevelopmentPasswordWithoutIncludingItInError() {
        MockEnvironment environment = publicEnvironment()
                .withProperty("campusai.demo-passwords.admin", DEV_PASSWORD);

        assertThatThrownBy(() -> new DemoAccounts(encoder, DEV_PASSWORD, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("differ from campusai.dev-password")
                .hasMessageNotContaining(DEV_PASSWORD);
    }

    @Test
    void publicDemoAcceptsExactlySixteenCharacters() {
        String minimumPassword = "0123456789abcdef";
        DemoAccounts accounts = new DemoAccounts(encoder, DEV_PASSWORD,
                publicEnvironment().withProperty("campusai.demo-passwords.student2", minimumPassword));

        assertThat(encoder.matches(minimumPassword,
                accounts.loadUserByUsername("student2").getPassword())).isTrue();
    }

    @Test
    void springRefusesToStartWithMissingPublicDemoPasswords() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(new MockEnvironment()
                    .withProperty("campusai.dev-password", DEV_PASSWORD)
                    .withProperty("campusai.public-demo", "true"));
            context.registerBean(PasswordEncoder.class, () -> encoder);
            context.register(DemoAccounts.class);

            assertThatThrownBy(context::refresh)
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("campusai.demo-passwords.admin")
                    .hasStackTraceContaining("at least 16 characters")
                    .hasMessageNotContaining(DEV_PASSWORD);
        }
    }

    @Test
    void springUsesEnvironmentAwareConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(publicEnvironment().withProperty("campusai.dev-password", DEV_PASSWORD));
            context.registerBean(PasswordEncoder.class, () -> encoder);
            context.register(DemoAccounts.class);
            context.refresh();

            DemoAccounts accounts = context.getBean(DemoAccounts.class);
            assertThat(encoder.matches(PASSWORDS.get("admin"),
                    accounts.loadUserByUsername("admin").getPassword())).isTrue();
        }
    }

    private static MockEnvironment publicEnvironment() {
        MockEnvironment environment = new MockEnvironment().withProperty("campusai.public-demo", "true");
        PASSWORDS.forEach((username, password) ->
                environment.setProperty("campusai.demo-passwords." + username, password));
        return environment;
    }
}
