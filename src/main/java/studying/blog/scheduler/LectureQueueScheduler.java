package studying.blog.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import studying.blog.service.QueueService;

import java.util.Set;

@Slf4j
@RequiredArgsConstructor
@Component
public class LectureQueueScheduler {

    private final QueueService queueService;

    @Scheduled(fixedDelay = 3000)
    public void processQueue(){
        Long lectureId = 1L; //테스트 1번 특강 고정
        int batchSize = 1; //한번에 3명 처리

        Set<String> admitted = queueService.popFront(lectureId, batchSize);

        if(!admitted.isEmpty()){
            queueService.markAdmitted(lectureId,admitted);
            log.info("입장 처리된 유저 수 : {}",admitted.size());
        }
    }
}
