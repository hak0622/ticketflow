package studying.blog.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.dto.EnrollmentResponse;
import studying.blog.dto.MyEnrollmentResponse;
import studying.blog.repository.EnrollmentRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MyPageService {
    private final EnrollmentRepository enrollmentRepository;

    @Transactional(readOnly = true)
    public List<MyEnrollmentResponse>getMyEnrollments(Long userId){
        return enrollmentRepository.findByUserIdWithLecture(userId)
                .stream()
                .map(MyEnrollmentResponse::from)
                .toList();
    }
}
