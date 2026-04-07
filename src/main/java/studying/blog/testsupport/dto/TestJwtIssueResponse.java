package studying.blog.testsupport.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TestJwtIssueResponse {

    private final Long userId;
    private final String email;
    private final String accessToken;

    public static TestJwtIssueResponse of(Long userId, String email, String accessToken) {
        return new TestJwtIssueResponse(userId, email, accessToken);
    }
}
