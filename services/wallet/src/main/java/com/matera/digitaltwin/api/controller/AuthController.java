package com.matera.digitaltwin.api.controller;

import com.matera.digitaltwin.api.model.UserInfo;
import com.matera.digitaltwin.api.service.GoogleAuthService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final GoogleAuthService googleAuthService;

    public AuthController(GoogleAuthService googleAuthService) {
        this.googleAuthService = googleAuthService;
    }

    @PostMapping("/google")
    public ResponseEntity<Map<String, Object>> googleLogin(
            @RequestBody Map<String, String> body,
            HttpSession session) {

        String accessToken = body.get("access_token");
        if (accessToken == null || accessToken.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing access_token"));
        }

        UserInfo user = googleAuthService.verify(accessToken);
        session.setAttribute("user", user);
        return ResponseEntity.ok(Map.of("user", user));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpSession session) {
        UserInfo user = (UserInfo) session.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Not authenticated"));
        }
        return ResponseEntity.ok(Map.of("user", user));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
