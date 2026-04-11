package studying.blog.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import studying.blog.dto.BookingAdminResponse;
import studying.blog.dto.ConcertAdminUpsertRequest;
import studying.blog.dto.ConcertResponse;
import studying.blog.service.ConcertService;

import java.util.List;

@Tag(name = "Admin", description = "어드민 API (ADMIN 권한 필요)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/concerts")
@SecurityRequirement(name = "bearerAuth")
public class AdminConcertApiController {
    private final ConcertService concertService;

    @Operation(summary = "공연 생성 (어드민)")
    @PostMapping
    public ResponseEntity<ConcertResponse> create(@RequestBody ConcertAdminUpsertRequest req){
        return ResponseEntity.ok(concertService.adminCreate(req));
    }

    @Operation(summary = "공연 수정 (어드민)")
    @PutMapping("/{id}")
    public ResponseEntity<ConcertResponse> update(@PathVariable Long id, @RequestBody ConcertAdminUpsertRequest req){
        return ResponseEntity.ok(concertService.adminUpdate(id, req));
    }

    @Operation(summary = "공연 마감 (어드민)")
    @PatchMapping("/{id}/close")
    public ResponseEntity<ConcertResponse> close(@PathVariable Long id){
        return ResponseEntity.ok(concertService.adminClose(id));
    }

    @Operation(summary = "공연별 예매 목록 조회 (어드민)")
    @GetMapping("/{id}/bookings")
    public ResponseEntity<List<BookingAdminResponse>> bookings(@PathVariable Long id){
        return ResponseEntity.ok(concertService.adminBookings(id));
    }

    @Operation(summary = "전체 공연 목록 조회 (어드민)")
    @GetMapping
    public ResponseEntity<List<ConcertResponse>> list(){
        return ResponseEntity.ok(concertService.findAll());
    }

    @Operation(summary = "공연 삭제 (어드민)")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id){
        concertService.adminDelete(id);
        return ResponseEntity.noContent().build();
    }
}
