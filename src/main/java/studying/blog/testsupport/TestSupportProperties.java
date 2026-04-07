package studying.blog.testsupport;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.test-support")
public class TestSupportProperties {

    private boolean enabled;
    private long tokenExpirationMinutes = 120;
    private List<String> seedEmails = new ArrayList<>();
}
