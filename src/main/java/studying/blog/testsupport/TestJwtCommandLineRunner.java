package studying.blog.testsupport;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import studying.blog.testsupport.dto.TestJwtIssueResponse;

@Component
@Profile({"local", "test"})
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.test-support", name = "enabled", havingValue = "true")
public class TestJwtCommandLineRunner implements CommandLineRunner {

    private final TestSupportProperties properties;
    private final TestJwtSupportService testJwtSupportService;

    @Override
    public void run(String... args) {
        for (String email : properties.getSeedEmails()) {
            TestJwtIssueResponse response = testJwtSupportService.issueByEmail(email);
            System.out.println(response.getEmail() + "," + response.getUserId() + "," + response.getAccessToken());
        }
    }
}
