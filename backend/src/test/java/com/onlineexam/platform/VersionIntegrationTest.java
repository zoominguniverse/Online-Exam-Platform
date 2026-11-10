package com.onlineexam.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.flyway.enabled=true")
class VersionIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Value("${EXAM_TEST_EXPECTED_VERSION:v1}") String expectedVersion;

    @Test
    void reportsTheVersionAndReadinessAfterMigration() {
        var response = http.getForEntity("/api/v1/version", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("code").asInt(-1)).isZero();
        assertThat(body.path("data").path("apiVersion").asText()).isEqualTo(expectedVersion);
        assertThat(body.path("requestId").asText()).isNotBlank();
        assertThat(http.getForEntity("/actuator/health/readiness", JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.grades')::text", String.class))
                .isEqualTo("grades");
    }
}
