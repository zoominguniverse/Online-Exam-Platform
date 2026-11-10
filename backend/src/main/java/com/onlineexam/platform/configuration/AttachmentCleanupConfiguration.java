package com.onlineexam.platform.configuration;

import com.onlineexam.platform.service.AttachmentService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableScheduling
class AttachmentCleanupConfiguration {
    private final AttachmentService attachments;

    AttachmentCleanupConfiguration(AttachmentService attachments) { this.attachments = attachments; }

    @Scheduled(initialDelay = 3600000, fixedDelay = 3600000)
    void clean() { attachments.clean(); }
}
