package studying.blog.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import studying.blog.testsupport.exception.TestSupportUserNotFoundException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TestSupportUserNotFoundException.class)
    public ResponseEntity<?> handleTestSupportUserNotFound(TestSupportUserNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "status", 404,
                "error", "NOT_FOUND",
                "message", e.getMessage()
        ));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleIllegalState(IllegalStateException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();

        //종료된 공연 / 마감 -> 403
        if(msg.contains("종료된") || msg.contains("마감된 콘서트")){
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "status",403,
                    "message",msg
            ));
        }

        //정원 마감 / 좌석 부족 등 충돌 -> 409
        if(msg.contains("정원") || msg.contains("SOLD_OUT")){
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "status",409,
                    "message",msg
            ));
        }

        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "status",409,
                "message",msg
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", 400,
                "error", e.getMessage()
        ));
    }
}
