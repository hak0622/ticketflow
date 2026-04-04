package studying.blog.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import studying.blog.dto.ConcertCreateRequest;
import studying.blog.dto.ConcertResponse;
import studying.blog.service.ConcertService;

@Controller
@RequiredArgsConstructor
@RequestMapping("/api/concerts")
public class ConcertApiController {
    private final ConcertService concertService;

    @PostMapping
    public ResponseEntity<ConcertResponse> create(@RequestBody ConcertCreateRequest req){
        return ResponseEntity.ok(concertService.create(req));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConcertResponse> findOne(@PathVariable Long id){
        return ResponseEntity.ok(concertService.findById(id));
    }
}
