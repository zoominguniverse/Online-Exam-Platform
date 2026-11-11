package com.onlineexam.platform.configuration;

import com.onlineexam.platform.service.AttemptLifecycle;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "exam.deadlines.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
class AttemptDeadlineConfiguration {
    private final AttemptLifecycle lifecycle;
    AttemptDeadlineConfiguration(AttemptLifecycle lifecycle) { this.lifecycle = lifecycle; }

    @Scheduled(initialDelay = 5000, fixedDelay = 5000)
    void freeze() { lifecycle.freezeDue(); }
}
