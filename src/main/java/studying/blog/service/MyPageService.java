package studying.blog.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.dto.MyBookingResponse;
import studying.blog.repository.BookingRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MyPageService {
    private final BookingRepository bookingRepository;

    @Transactional(readOnly = true)
    public List<MyBookingResponse> getMyBookings(Long userId){
        return bookingRepository.findByUserIdWithConcert(userId)
                .stream()
                .map(MyBookingResponse::from)
                .toList();
    }
}
