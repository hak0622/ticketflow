package studying.blog.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.FORBIDDEN)
public class MockPaymentDisabledException extends RuntimeException {
    public MockPaymentDisabledException() {
        super("이 환경에서는 Mock 결제를 사용할 수 없습니다.");
    }
}
