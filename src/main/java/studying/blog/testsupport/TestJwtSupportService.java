package studying.blog.testsupport;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import studying.blog.config.TokenProvider;
import studying.blog.domain.User;
import studying.blog.repository.UserRepository;
import studying.blog.testsupport.dto.TestJwtIssueResponse;
import studying.blog.testsupport.exception.TestSupportUserNotFoundException;

import java.time.Duration;
import java.util.List;
import java.util.StringJoiner;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.test-support", name = "enabled", havingValue = "true")
public class TestJwtSupportService {

    private final UserRepository userRepository;
    private final TokenProvider tokenProvider;
    private final TestSupportProperties properties;
    private final TestUserSeedService testUserSeedService;

    public TestJwtIssueResponse issueByEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new TestSupportUserNotFoundException(
                        "테스트 유저를 찾을 수 없습니다. email=" + email
                ));
        return issue(user);
    }

    public TestJwtIssueResponse issueByUserId(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new TestSupportUserNotFoundException(
                        "테스트 유저를 찾을 수 없습니다. userId=" + userId
                ));
        return issue(user);
    }

    public String generateFreshCsvContent() {
        List<User> users = testUserSeedService.seedUsers();
        StringJoiner lines = new StringJoiner("\n", "", "\n");
        lines.add("email,userId,token");
        for (User user : users) {
            String token = tokenProvider.generateToken(user, Duration.ofMinutes(properties.getTokenExpirationMinutes()));
            lines.add(user.getEmail() + "," + user.getId() + "," + token);
        }
        return lines.toString();
    }

    private TestJwtIssueResponse issue(User user) {
        String accessToken = tokenProvider.generateToken(
                user,
                Duration.ofMinutes(properties.getTokenExpirationMinutes())
        );

        return TestJwtIssueResponse.of(user.getId(), user.getEmail(), accessToken);
    }
}
