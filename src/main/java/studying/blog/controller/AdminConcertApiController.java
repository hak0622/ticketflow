package studying.blog.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import studying.blog.dto.BookingAdminResponse;
import studying.blog.dto.ConcertAdminUpsertRequest;
import studying.blog.dto.ConcertResponse;
import studying.blog.service.ConcertService;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/concerts")
public class AdminConcertApiController {
    private final ConcertService concertService;

    @PostMapping
    public ResponseEntity<ConcertResponse> create(@RequestBody ConcertAdminUpsertRequest req){
        return ResponseEntity.ok(concertService.adminCreate(req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ConcertResponse> update(@PathVariable Long id, @RequestBody ConcertAdminUpsertRequest req){
        return ResponseEntity.ok(concertService.adminUpdate(id, req));
    }

    @PatchMapping("/{id}/close")
    public ResponseEntity<ConcertResponse> close(@PathVariable Long id){
        return ResponseEntity.ok(concertService.adminClose(id));
    }

    @GetMapping("/{id}/bookings")
    public ResponseEntity<List<BookingAdminResponse>> bookings(@PathVariable Long id){
        return ResponseEntity.ok(concertService.adminBookings(id));
    }

    @GetMapping
    public ResponseEntity<List<ConcertResponse>> list(){
        return ResponseEntity.ok(concertService.findAll());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id){
        concertService.adminDelete(id);
        return ResponseEntity.noContent().build();
    }
}
