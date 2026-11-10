package com.onlineexam.platform.configuration;

import com.onlineexam.platform.service.IdentityService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
@Profile("migration")
class InitialIdentityConfiguration {
    @Bean
    ApplicationRunner initialIdentity(IdentityService identity, JdbcClient jdbc,
            @Value("${BOOTSTRAP_ADMIN_USERNAME:admin}") String name,
            @Value("${BOOTSTRAP_ADMIN_PASSWORD:}") String password,
            @Value("${EXAM_SEED_DEV_GRADE:false}") boolean seedGrade) {
        return args -> {
            if (!password.isBlank()) identity.bootstrap(name, password);
            if (seedGrade) {
                jdbc.sql("INSERT INTO grades (id, name) VALUES (:id, '演示年级') ON CONFLICT (id) DO NOTHING")
                        .param("id", UUID.fromString("62f9b743-a13e-4267-8810-3f41c8120110")).update();
            }
        };
    }
}
