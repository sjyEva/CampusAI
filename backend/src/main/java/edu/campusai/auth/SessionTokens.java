package edu.campusai.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class SessionTokens {
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(30);

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public String issue(Authentication authentication) {
        Instant now = Instant.now();
        sessions.values().removeIf(session -> session.isExpired(now));

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, new Session(authentication, now.plus(TOKEN_LIFETIME)));
        return token;
    }

    public Authentication authenticate(String header) {
        String token = fromHeader(header);
        if (token == null) {
            return null;
        }

        Session session = sessions.get(token);
        if (session == null) {
            return null;
        }
        if (session.isExpired(Instant.now())) {
            sessions.remove(token, session);
            return null;
        }
        return session.authentication();
    }

    public void revoke(String header) {
        String token = fromHeader(header);
        if (token != null) {
            sessions.remove(token);
        }
    }

    private static String fromHeader(String header) {
        String prefix = "Bearer ";
        if (header != null && header.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return header.substring(prefix.length());
        }
        return null;
    }

    private record Session(Authentication authentication, Instant expiresAt) {
        boolean isExpired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }
}
