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

    @Scheduled(fixedDelay = 1000)
    public void processQueue(){
        Long lectureId = 1L; //테스트 1번 특강 고정
        int batchSize = 50; //한번에 n명 처리

        Set<String> admittedUserIds = queueService.popFront(lectureId, batchSize);

        if(!admittedUserIds.isEmpty()){
            queueService.markAdmitted(lectureId,admittedUserIds);
            log.info("입장 처리된 유저 수 : {}",admittedUserIds.size());
        }
    }
}
