package studying.blog.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.domain.Concert;
import studying.blog.domain.ConcertStatus;
import studying.blog.dto.BookingAdminResponse;
import studying.blog.dto.ConcertAdminUpsertRequest;
import studying.blog.dto.ConcertCreateRequest;
import studying.blog.dto.ConcertResponse;
import studying.blog.dto.ConcertSearchCondition;
import studying.blog.repository.BookingRepository;
import studying.blog.repository.ConcertRepository;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Service
public class ConcertService {
    private final ConcertRepository concertRepository;
    private final BookingRepository bookingRepository;

    @Transactional
    public ConcertResponse create(ConcertCreateRequest req){
        Concert concert = Concert.builder()
                .title(req.getTitle())
                .eventAt(req.getEventAt())
                .totalSeats(req.getTotalSeats())
                .bookedCount(0)
                .status(ConcertStatus.OPEN)
                .posterUrl(req.getPosterUrl())
                .build();

        return ConcertResponse.from(concertRepository.save(concert));
    }

    @Transactional(readOnly = true)
    public ConcertResponse findById(Long id){
        Concert concert = concertRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Concert not found : " + id));

        return ConcertResponse.from(concert);
    }

    @Transactional(readOnly = true)
    public List<ConcertResponse> findAll(String genre){
        String normalizedGenre = genre == null ? null : genre.trim();
        List<Concert> concerts = (normalizedGenre == null || normalizedGenre.isBlank())
                ? concertRepository.findAll()
                : concertRepository.findByGenre(normalizedGenre);
        List<ConcertResponse> result = new ArrayList<>();

        for (Concert concert : concerts) {
            result.add(ConcertResponse.from(concert));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<ConcertResponse> findAll() {
        return findAll(null);
    }

    @Transactional(readOnly = true)
    public List<ConcertResponse> search(ConcertSearchCondition condition) {
        return concertRepository.search(condition).stream()
                .map(ConcertResponse::from)
                .toList();
    }

    @Transactional
    public ConcertResponse adminCreate(ConcertAdminUpsertRequest req){
        validateAdminUpsert(req);

        Concert concert = Concert.builder()
                .title(req.getTitle())
                .eventAt(req.getEventAt())
                .totalSeats(req.getTotalSeats())
                .bookedCount(0)
                .status(req.getStatus() == null ? ConcertStatus.OPEN : req.getStatus())
                .posterUrl(req.getPosterUrl())
                .build();
        return ConcertResponse.from(concertRepository.save(concert));
    }

    @Transactional
    public ConcertResponse adminUpdate(Long id, ConcertAdminUpsertRequest req){
        validateAdminUpsert(req);

        Concert concert = concertRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Concert not found : " + id));

        if(req.getTotalSeats() < concert.getBookedCount()){
            throw new IllegalArgumentException("totalSeats는 현재 예매 인원(bookedCount)보다 작을 수 없습니다. 현재 예매 인원: " + concert.getBookedCount());
        }

        concert.updateByAdmin(req.getTitle(), req.getEventAt(), req.getTotalSeats(), req.getStatus());
        return ConcertResponse.from(concert);
    }

    @Transactional
    public ConcertResponse adminClose(Long id){
        Concert concert = concertRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Concert not found : " + id));
        concert.close();
        return ConcertResponse.from(concert);
    }

    @Transactional
    public void adminDelete(Long id){
        Concert concert = concertRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Concert not found : " + id));
        bookingRepository.deleteByConcert(concert);
        concertRepository.delete(concert);
    }

    @Transactional(readOnly = true)
    public List<BookingAdminResponse> adminBookings(Long concertId) {
        return bookingRepository.findAdminBookings(concertId);
    }

    private void validateAdminUpsert(ConcertAdminUpsertRequest req){
        if(req.getTitle() == null || req.getTitle().isBlank()){
            throw new IllegalArgumentException("title은 필수입니다.");
        }
        if(req.getEventAt() == null){
            throw new IllegalArgumentException("eventAt은 필수입니다.");
        }
        if(req.getTotalSeats() <= 0){
            throw new IllegalArgumentException("totalSeats는 1 이상이어야 합니다.");
        }
    }
}
