package studying.blog.testsupport.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TestSetupResponse {

    private final int admittedCount;

    public static TestSetupResponse of(int admittedCount) {
        return new TestSetupResponse(admittedCount);
    }
}
