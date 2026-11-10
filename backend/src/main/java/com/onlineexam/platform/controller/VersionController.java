package com.onlineexam.platform.controller;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class VersionController {
    @GetMapping("/api/v1/version")
    ApiResponse<Map<String, String>> version() {
        return ApiResponse.ok(Map.of("apiVersion", "v1"));
    }
}
