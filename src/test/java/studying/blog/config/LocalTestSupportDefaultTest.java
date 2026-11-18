package studying.blog.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class LocalTestSupportDefaultTest {
    @Test void localDefaultsToDisabledAndAllowsExplicitEnvironmentOptIn() throws Exception {
        var environment = new MockEnvironment();
        for (var source : new YamlPropertySourceLoader().load("local", new ClassPathResource("application-local.yml")))
            environment.getPropertySources().addLast(source);
        assertThat(environment.getProperty("app.test-support.enabled", Boolean.class)).isFalse();
        environment.setProperty("TEST_SUPPORT_ENABLED", "true");
        assertThat(environment.getProperty("app.test-support.enabled", Boolean.class)).isTrue();
    }
}
