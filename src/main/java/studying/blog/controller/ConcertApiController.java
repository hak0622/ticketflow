package studying.blog.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import studying.blog.dto.ConcertCreateRequest;
import studying.blog.dto.ConcertResponse;
import studying.blog.service.ConcertService;

import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/api/concerts")
public class ConcertApiController {
    private final ConcertService concertService;

    @GetMapping
    public ResponseEntity<List<ConcertResponse>> list(){
        return ResponseEntity.ok(concertService.findAll());
    }

    @PostMapping
    public ResponseEntity<ConcertResponse> create(@RequestBody ConcertCreateRequest req){
        return ResponseEntity.ok(concertService.create(req));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConcertResponse> findOne(@PathVariable Long id){
        return ResponseEntity.ok(concertService.findById(id));
    }
}
