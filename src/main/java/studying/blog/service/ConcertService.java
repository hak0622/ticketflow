package studying.blog.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
    private final QueueService queueService;

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

        Concert saved = concertRepository.save(concert);
        queueService.initSeatCount(saved.getId(), saved.getTotalSeats());
        return toConcertResponse(saved);
    }

    @Transactional(readOnly = true)
    public ConcertResponse findById(Long id){
        Concert concert = concertRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Concert not found : " + id));

        return toConcertResponse(concert);
    }

    @Transactional(readOnly = true)
    public List<ConcertResponse> findAll(String genre){
        String normalizedGenre = genre == null ? null : genre.trim();
        List<Concert> concerts = (normalizedGenre == null || normalizedGenre.isBlank())
                ? concertRepository.findAll()
                : concertRepository.findByGenre(normalizedGenre);
        List<ConcertResponse> result = new ArrayList<>();

        for (Concert concert : concerts) {
            result.add(toConcertResponse(concert));
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
                .map(this::toConcertResponse)
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

        Concert saved = concertRepository.save(concert);
        queueService.initSeatCount(saved.getId(), saved.getTotalSeats());
        return toConcertResponse(saved);
    }

    @Transactional
    public ConcertResponse adminUpdate(Long id, ConcertAdminUpsertRequest req){
        validateAdminUpsert(req);

        Concert concert = concertRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Concert not found : " + id));
        int derivedBookedCount = resolveBookedCount(concert);

        if(req.getTotalSeats() < derivedBookedCount){
            throw new IllegalArgumentException("totalSeats는 현재 예매 인원(bookedCount)보다 작을 수 없습니다. 현재 예매 인원: " + derivedBookedCount);
        }

        int prevTotalSeats = concert.getTotalSeats();
        concert.updateByAdmin(req.getTitle(), req.getEventAt(), req.getTotalSeats(), req.getStatus());

        if (req.getTotalSeats() != prevTotalSeats) {
            int remaining = Math.max(0, req.getTotalSeats() - derivedBookedCount);
            queueService.initSeatCount(id, remaining);
        }

        // 캐시 갱신은 커밋 이후 — 롤백 시 Redis/DB 불일치 방지
        final ConcertStatus updatedStatus = concert.getStatus();
        final String updatedTitle = concert.getTitle();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                queueService.setConcertInfo(id, updatedStatus, updatedTitle);
            }
        });

        return toConcertResponse(concert);
    }

    @Transactional
    public ConcertResponse adminClose(Long id){
        Concert concert = concertRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Concert not found : " + id));
        concert.close();
        final String title = concert.getTitle();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                queueService.setConcertInfo(id, ConcertStatus.CLOSED, title);
            }
        });
        return toConcertResponse(concert);
    }

    @Transactional
    public void adminDelete(Long id){
        Concert concert = concertRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Concert not found : " + id));
        bookingRepository.deleteByConcert(concert);
        concertRepository.delete(concert);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                queueService.deleteSeatCount(id);
                queueService.deleteConcertStatus(id);
            }
        });
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

    private ConcertResponse toConcertResponse(Concert concert) {
        return ConcertResponse.from(concert, resolveBookedCount(concert));
    }

    private int resolveBookedCount(Concert concert) {
        Long remaining = queueService.getRemainingSeat(concert.getId());
        if (remaining == null) {
            org.slf4j.LoggerFactory.getLogger(ConcertService.class)
                    .warn("[BOOKED_COUNT][FALLBACK] concertId={} usingPersistedValue={}",
                            concert.getId(), concert.getBookedCount());
            return concert.getBookedCount();
        }

        long bookedCount = (long) concert.getTotalSeats() - remaining;
        if (bookedCount < 0) {
            org.slf4j.LoggerFactory.getLogger(ConcertService.class)
                    .warn("[BOOKED_COUNT][NEGATIVE] concertId={} totalSeats={} remaining={} usingZero",
                            concert.getId(), concert.getTotalSeats(), remaining);
            return 0;
        }
        if (bookedCount > Integer.MAX_VALUE) {
            return concert.getBookedCount();
        }
        return (int) bookedCount;
    }
}
