package com.onlineexam.platform.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.http.ResponseCookie;

public final class WebSession {
    private static final String NAME = "__Host-exam_session";

    private WebSession() {}

    public static String token(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    public static ResponseCookie cookie(String token) {
        return ResponseCookie.from(NAME, token == null ? "" : token)
                .path("/").secure(true).httpOnly(true).sameSite("Lax")
                .maxAge(token == null ? Duration.ZERO : Duration.ofHours(12)).build();
    }
}
