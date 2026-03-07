package com.matera.digitaltwin.api.service;

import com.matera.digitaltwin.api.model.UserInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class GoogleAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleAuthService.class);
    private static final String USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo";

    @Value("${google.allowed-domains}")
    private List<String> allowedDomains;

    private final RestClient restClient = RestClient.create();
    private final JdbcTemplate jdbc;

    public GoogleAuthService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UserInfo verify(String accessToken) {
        GoogleUserInfo info;
        try {
            info = restClient.get()
                    .uri(USERINFO_URL)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(GoogleUserInfo.class);
        } catch (Exception e) {
            log.warn("Google userinfo call failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid access token");
        }

        if (info == null || info.email() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token response");
        }

        boolean domainAllowed = allowedDomains.stream()
                .anyMatch(d -> info.email().endsWith("@" + d));
        if (!domainAllowed) {
            log.warn("Login attempt from non-allowed domain: {}", info.email());
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Access restricted to: " + allowedDomains.stream().map(d -> "@" + d).toList());
        }

        String status;
        try {
            status = jdbc.queryForObject(
                    "SELECT status FROM digitaltwinapp.users WHERE email = ?",
                    String.class, info.email());
        } catch (EmptyResultDataAccessException e) {
            log.info("Auto-provisioning new user: {}", info.email());
            jdbc.update(
                    "INSERT INTO digitaltwinapp.users (id, email, name, status) VALUES (gen_random_uuid(), ?, ?, 'active')",
                    info.email(), info.name());
            status = "active";
        }

        if ("suspended".equals(status)) {
            log.warn("Login attempt from suspended account: {}", info.email());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is suspended");
        }

        return new UserInfo(info.email(), info.name(), info.picture());
    }

    private record GoogleUserInfo(String email, String name, String picture) {}
}
