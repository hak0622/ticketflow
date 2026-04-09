package studying.blog.testsupport;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import studying.blog.domain.User;
import studying.blog.dto.AddUserRequest;
import studying.blog.repository.UserRepository;
import studying.blog.service.UserService;

import java.util.ArrayList;
import java.util.List;

@Service
@Profile({"local", "test"})
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.test-support", name = "enabled", havingValue = "true")
public class TestUserSeedService {

    private final TestSupportProperties properties;
    private final UserRepository userRepository;
    private final UserService userService;

    public List<User> seedUsers() {
        List<User> users = new ArrayList<>();
        for (int index = 1; index <= properties.getUserCount(); index++) {
            users.add(findOrCreateUser(index));
        }
        return users;
    }

    private User findOrCreateUser(int index) {
        String email = buildEmail(index);

        return userRepository.findByEmail(email)
                .orElseGet(() -> {
                    AddUserRequest request = new AddUserRequest();
                    request.setEmail(email);
                    request.setPassword(properties.getDefaultPassword());

                    userService.save(request);

                    return userRepository.findByEmail(email)
                            .orElseThrow(() -> new IllegalStateException(
                                    "테스트 유저 생성 후 조회 실패. email=" + email
                            ));
                });
    }

    private String buildEmail(int index) {
        return properties.getEmailPrefix() + index + "@" + properties.getEmailDomain();
    }
}
