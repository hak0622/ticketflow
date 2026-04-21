package studying.blog.testsupport;

public enum TestSetupMode {
    /** 대기열/폴링 테스트용: 상태만 초기화, admitted 키 발급 없음 */
    QUEUE,
    /** 예매 burst 테스트용: 상태 초기화 + 전체 유저 admitted 키 발급 */
    BOOKING
}
