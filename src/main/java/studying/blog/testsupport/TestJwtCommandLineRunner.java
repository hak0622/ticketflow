package studying.blog.testsupport;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import studying.blog.config.TokenProvider;
import studying.blog.domain.User;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@Profile({"local", "test"})
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.test-support", name = "enabled", havingValue = "true")
public class TestJwtCommandLineRunner implements CommandLineRunner {

    private final TestSupportProperties properties;
    private final TestUserSeedService testUserSeedService;
    private final TokenProvider tokenProvider;

    @Override
    public void run(String... args) {
        List<User> users = testUserSeedService.seedUsers();
        List<String> lines = buildCsvLines(users);
        writeCsv(lines);

        for (int i = 1; i < lines.size(); i++) {
            System.out.println(lines.get(i));
        }
    }

    private List<String> buildCsvLines(List<User> users) {
        List<String> lines = new ArrayList<>();
        lines.add("email,userId,token");

        for (User user : users) {
            String token = tokenProvider.generateToken(
                    user,
                    Duration.ofMinutes(properties.getTokenExpirationMinutes())
            );
            lines.add(user.getEmail() + "," + user.getId() + "," + token);
        }

        return lines;
    }

    private void writeCsv(List<String> lines) {
        try {
            Path outputPath = Path.of(properties.getCsvOutputPath());
            Files.write(
                    outputPath,
                    lines,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            throw new IllegalStateException("booking-users.csv 저장 실패", e);
        }
    }
}
