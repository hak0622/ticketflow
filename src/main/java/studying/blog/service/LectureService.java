package studying.blog.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.domain.Lecture;
import studying.blog.dto.LectureCreateRequest;
import studying.blog.dto.LectureResponse;
import studying.blog.repository.LectureRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class LectureService {
    private final LectureRepository lectureRepository;

    @Transactional
    public LectureResponse create(LectureCreateRequest req){
        Lecture lecture = Lecture.builder()
                .title(req.getTitle())
                .openAt(req.getOpenAt())
                .capacity(req.getCapacity())
                .build();

        return LectureResponse.from(lectureRepository.save(lecture));
    }

    @Transactional(readOnly = true)
    public LectureResponse findById(Long id){
        Lecture lecture = lectureRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Lecture not found : " + id));

        return LectureResponse.from(lecture);
    }

    @Transactional(readOnly = true)
    public List<LectureResponse> findAll(){
        List<Lecture> lectures = lectureRepository.findAll();
        List<LectureResponse>result = new ArrayList<>();

        for (Lecture lecture : lectures) {
            result.add(LectureResponse.from(lecture));
        }
        return result;
    }
}
