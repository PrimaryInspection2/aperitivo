package dev.amishko.aperitivo.identity.config;

import dev.amishko.aperitivo.config.BaseIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies the {@code iamAuditTaskExecutor} bean wiring:
 *
 * <ul>
 *   <li>bean is present under the exact qualifier {@code "iamAuditTaskExecutor"}
 *       — this is the string that {@code @Async("iamAuditTaskExecutor")} on
 *       {@code recordSuccessfulUse} / {@code recordFailure} resolves against.
 *       A typo here would silently fall back to Spring's default {@code SimpleAsyncTaskExecutor},
 *       which spawns an unbounded thread per call — a real production hazard;</li>
 *   <li>the underlying {@link ThreadPoolTaskExecutor} is configured with the
 *       values from {@link AuditExecutorProperties} (core/max/queue) and
 *       uses {@link ThreadPoolExecutor.CallerRunsPolicy} as the saturation policy;</li>
 *   <li>a real {@code @Async("iamAuditTaskExecutor")} call is executed on a
 *       thread whose name starts with the configured prefix — proving the
 *       qualifier resolution actually reaches this executor at runtime.</li>
 * </ul>
 */
@Import(IamAuditTaskExecutorIT.AsyncQualifierProbe.class)
class IamAuditTaskExecutorIT extends BaseIT {

    @Autowired
    @Qualifier("iamAuditTaskExecutor")
    private TaskExecutor iamAuditTaskExecutor;

    @Autowired
    private AsyncQualifierProbe asyncProbe;

    @Test
    @DisplayName("bean is a ThreadPoolTaskExecutor with configured pool sizes and prefix")
    void beanIsConfiguredThreadPoolTaskExecutor() {
        assertThat(iamAuditTaskExecutor)
                .as("qualifier used by @Async(\"iamAuditTaskExecutor\") must resolve to a ThreadPoolTaskExecutor")
                .isInstanceOf(ThreadPoolTaskExecutor.class);

        ThreadPoolTaskExecutor tpte = (ThreadPoolTaskExecutor) iamAuditTaskExecutor;
        assertThat(tpte.getCorePoolSize()).isEqualTo(2);
        assertThat(tpte.getMaxPoolSize()).isEqualTo(4);
        assertThat(tpte.getQueueCapacity()).isEqualTo(500);
        assertThat(tpte.getThreadNamePrefix()).isEqualTo("iam-audit-");
    }

    @Test
    @DisplayName("saturation policy is CallerRunsPolicy — audit tasks never silently drop")
    void saturationPolicyIsCallerRuns() {
        ThreadPoolTaskExecutor tpte = (ThreadPoolTaskExecutor) iamAuditTaskExecutor;
        assertThat(tpte.getThreadPoolExecutor().getRejectedExecutionHandler())
                .as("CallerRunsPolicy is the intended back-pressure — losing an audit event is worse than blocking briefly")
                .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }

    @Test
    @DisplayName("@Async(\"iamAuditTaskExecutor\") resolves the qualifier and executes on iam-audit-* threads")
    void asyncQualifierExecutesOnIamAuditThread() throws Exception {
        AtomicReference<String> observedThreadName = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        assertThatCode(() -> asyncProbe.run(observedThreadName, done)).doesNotThrowAnyException();

        assertThat(done.await(3, TimeUnit.SECONDS))
                .as("async task must complete within 3s")
                .isTrue();
        assertThat(observedThreadName.get())
                .as("thread name proves the async task actually ran on iamAuditTaskExecutor, not the default")
                .startsWith("iam-audit-");
    }

    /**
     * Test-only bean whose sole purpose is to expose an
     * {@code @Async("iamAuditTaskExecutor")}-annotated method so we can prove
     * the qualifier resolves to the intended executor.
     *
     * <p>{@link TestComponent} keeps this out of component-scan; it lands in
     * the context only via {@link Import} on this IT class.
     */
    @TestComponent
    static class AsyncQualifierProbe {

        @Async("iamAuditTaskExecutor")
        public void run(AtomicReference<String> observedThreadName, CountDownLatch done) {
            observedThreadName.set(Thread.currentThread().getName());
            done.countDown();
        }
    }
}
