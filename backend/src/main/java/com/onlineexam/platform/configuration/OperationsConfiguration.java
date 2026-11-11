package com.onlineexam.platform.configuration;
import com.onlineexam.platform.service.ObservabilityService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
@Configuration(proxyBeanMethods=false)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@EnableScheduling
class OperationsConfiguration {
    private final ObservabilityService metrics;
    OperationsConfiguration(ObservabilityService metrics) { this.metrics=metrics; }
    @Scheduled(initialDelay=5000,fixedDelay=5000) void inspect() { metrics.inspect(); }
}
