package com.onlineexam.platform;

import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class ExamPlatformApplication {
    public static void main(String[] args) {
        for (String name : List.of("DATABASE_URL", "DATABASE_USER", "DATABASE_PASSWORD")) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Missing required setting: " + name);
            }
        }

        var application = new SpringApplication(ExamPlatformApplication.class);
        if (args.length == 1 && "--migrate".equals(args[0])) {
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setAdditionalProfiles("migration");
            application.run("--spring.flyway.enabled=true").close();
            return;
        }
        application.run(args);
    }
}
