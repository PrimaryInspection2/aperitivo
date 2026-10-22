package dev.amishko.aperitivo.identity.service.strava;

import dev.amishko.aperitivo.config.BaseIT;
import dev.amishko.aperitivo.identity.dto.IdentityProvider;
import dev.amishko.aperitivo.identity.dto.LoginResult;
import dev.amishko.aperitivo.identity.dto.ProviderIdentityProfile;
import dev.amishko.aperitivo.identity.events.dto.UserRegisteredEvent;
import dev.amishko.aperitivo.identity.repository.UserIdentityRepository;
import dev.amishko.aperitivo.identity.service.OAuth2GoogleLoginCallbackService;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two concurrent first logins for the same {@code (provider, sub)} must provision exactly one user
 * (plan step 2, DoD). The barrier holds both threads right after their initial lookup returned
 * empty, so both transactions are guaranteed to race on the unique constraint.
 */
@Import(FirstLoginRaceIT.IdentityLookupHookConfig.class)
class FirstLoginRaceIT extends BaseIT {

    private static final int RACERS = 2;

    @Autowired
    private OAuth2GoogleLoginCallbackService loginService;

    /**
     * Runs right after every real {@code findByProviderAndProviderUserId} call; no-op outside this test.
     */
    private static volatile Runnable afterIdentityLookup = () -> {
    };

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("two parallel first logins → one user, one identity, same userId, one UserRegisteredEvent")
    void parallelFirstLoginsProvisionExactlyOneUser() throws Exception {
        CyclicBarrier bothLookedUp = new CyclicBarrier(RACERS);
        AtomicInteger lookups = new AtomicInteger();
        afterIdentityLookup = () -> {
            // Only the first lookup of each racer waits; the retry's lookup must pass straight through.
            if (lookups.incrementAndGet() <= RACERS) {
                awaitQuietly(bothLookedUp);
            }
        };

        ProviderIdentityProfile profile = new ProviderIdentityProfile(
                IdentityProvider.GOOGLE, "google-sub-" + UUID.randomUUID(),
                "racer@example.com", "Racer", null);

        List<LoginResult> results;
        try (ExecutorService pool = Executors.newFixedThreadPool(RACERS)) {
            List<Future<LoginResult>> futures = List.of(
                    pool.submit(() -> loginService.handleLogin(profile)),
                    pool.submit(() -> loginService.handleLogin(profile)));
            results = List.of(futures.get(0).get(30, SECONDS), futures.get(1).get(30, SECONDS));
        } finally {
            afterIdentityLookup = () -> {
            };
        }

        assertThat(lookups).as("the loser retried exactly once: 2 initial lookups + 1 retry lookup")
                .hasValue(RACERS + 1);
        assertThat(count("identity.users")).as("users rows").isEqualTo(1);
        assertThat(count("identity.user_identities")).as("user_identities rows").isEqualTo(1);

        assertThat(results).extracting(LoginResult::userId)
                .as("both requests resolve to the same user")
                .containsOnly(results.getFirst().userId());
        assertThat(results).filteredOn(LoginResult::isNewUser)
                .as("exactly one request registered the user")
                .hasSize(1);


        assertThat(recordedEvents.userRegisteredAfterCommit())
                .as("only the committed registration publishes UserRegisteredEvent")
                .extracting(UserRegisteredEvent::id)
                .containsExactly(results.getFirst().userId());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static void awaitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await(10, SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("racers did not meet at the barrier", e);
        }
    }

    /**
     * Wraps the Spring Data repository proxy instead of using {@code @MockitoSpyBean}: Mockito cannot
     * call the real method of an interface-based repository proxy.
     */
    @TestConfiguration
    static class IdentityLookupHookConfig {

        @Bean
        static BeanPostProcessor identityLookupHook() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof UserIdentityRepository)) {
                        return bean;
                    }
                    ProxyFactory factory = new ProxyFactory(bean);
                    factory.addAdvice((MethodInterceptor) invocation -> {
                        Object result = invocation.proceed();
                        if (invocation.getMethod().getName().equals("findByProviderAndProviderUserId")) {
                            afterIdentityLookup.run();
                        }
                        return result;
                    });
                    return factory.getProxy();
                }
            };
        }
    }
}