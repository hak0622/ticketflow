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
    private int userCount = 100;
    private String emailPrefix = "test";
    private String emailDomain = "test.com";
    private String defaultPassword = "password123!";
    private String nicknamePrefix = "test-user-";
    private String csvOutputPath = "booking-users.csv";
    private long tokenExpirationMinutes = 120;
    private List<String> seedEmails = new ArrayList<>();
}
