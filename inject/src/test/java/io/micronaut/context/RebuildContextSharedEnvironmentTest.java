package io.micronaut.context;

import io.micronaut.context.env.Environment;
import io.micronaut.context.env.PropertySource;
import io.micronaut.context.event.ShutdownEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproducer for #12829: when a second ApplicationContext is built reusing an
 * existing (already-managed) Environment instance -- as @MicronautTest(rebuildContext = true)
 * is suspected to do -- a ShutdownEvent listener on the second context should still
 * observe live property values, matching what it saw on startup.
 */
class RebuildContextSharedEnvironmentTest {

    @Test
    void shutdownListenerOnRebuiltContextSeesLiveProperties() {
        AtomicBoolean sawEnabledOnShutdown = new AtomicBoolean(false);

        // First context: owns and manages its own environment (normal startup)
        DefaultApplicationContext first = new DefaultApplicationContext("test");
        first.getEnvironment().addPropertySource(PropertySource.of("test", Map.of("app.enabled", true)));
        first.start();

        Environment sharedEnvironment = first.getEnvironment();
        first.stop(); // simulate first context tearing down between test methods

        // Second context: built reusing the SAME environment instance,
        // simulating what @MicronautTest(rebuildContext = true) likely does
        DefaultApplicationContext second = new DefaultApplicationContext(
                new ApplicationContextConfiguration() {
                    @Override
                    public List<String> getEnvironments() {
                        return List.of("test");
                    }
                },
                sharedEnvironment
        );
        second.registerSingleton(new ShutdownListener(sawEnabledOnShutdown, sharedEnvironment));
        second.start();

        second.stop(); // this should fire ShutdownEvent while properties are still live

        assertTrue(sawEnabledOnShutdown.get(),
                "ShutdownEvent listener on rebuilt context should see 'app.enabled=true', " +
                        "matching what was set at startup");
    }

    @Singleton
    static class ShutdownListener {
        private final AtomicBoolean result;
        private final Environment environment;

        ShutdownListener(AtomicBoolean result, Environment environment) {
            this.result = result;
            this.environment = environment;
        }

        @EventListener
        void onShutdown(ShutdownEvent event) {
            boolean enabled = environment.getProperty("app.enabled", Boolean.class, false);
            result.set(enabled);
        }
    }
}