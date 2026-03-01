package studying.blog.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import studying.blog.domain.Lecture;
import studying.blog.domain.LectureStatus;
import studying.blog.repository.LectureRepository;
import studying.blog.service.QueueService;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
public class LectureQueueScheduler {

    private final QueueService queueService;
    private final LectureRepository lectureRepository;

    @Scheduled(fixedDelay = 5000)
    public void processQueue(){
        int batchSize = 50; //한번에 n명 처리
        int ttlSeconds = 600; //입장권 TTL (5분)

        List<Lecture> openLectures = lectureRepository.findByStatus(LectureStatus.OPEN);

        if(openLectures.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();

        for(Lecture lecture : openLectures){
            Long lectureId = lecture.getId();

            try{
                //openAt 전이면 아직 입장권 발급 안 함(정각 오픈 느낌)
                if(lecture.getOpenAt() != null && now.isBefore(lecture.getOpenAt())){
                    continue;
                }

                //혹시 상태가 변경되면 스킵
                if(lecture.getStatus() == LectureStatus.CLOSED || lecture.getStatus() == LectureStatus.SOLD_OUT){
                    continue;
                }

                List<String> granted = queueService.popAndGrantAdmitted(lectureId, batchSize, ttlSeconds);

                if(!granted.isEmpty()){
                    log.info("[QueueScheduler] lectureId={} granted={}", lectureId, granted.size());
                }
            }catch (Exception e){
                log.error("[QueueScheduler] lectureId={} error={}", lectureId, e.getMessage(), e);
            }
        }
    }
}
