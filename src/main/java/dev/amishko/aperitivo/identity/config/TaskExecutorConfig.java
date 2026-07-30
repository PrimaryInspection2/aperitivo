package dev.amishko.aperitivo.identity.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@RequiredArgsConstructor
public class TaskExecutorConfig {

    private final AuditExecutorProperties auditExecutorProperties;

    @Bean
    public TaskExecutor iamAuditTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(auditExecutorProperties.corePoolSize());
        executor.setMaxPoolSize(auditExecutorProperties.maxPoolSize());
        executor.setQueueCapacity(auditExecutorProperties.queueCapacity());
        executor.setThreadNamePrefix(auditExecutorProperties.threadNamePrefix());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
