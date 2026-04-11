package studying.blog.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import studying.blog.dto.ConcertCreateRequest;
import studying.blog.dto.ConcertResponse;
import studying.blog.dto.ConcertSearchCondition;
import studying.blog.service.ConcertService;

import java.util.List;

@Tag(name = "Concert", description = "공연 API")
@Controller
@RequiredArgsConstructor
@RequestMapping("/api/concerts")
public class ConcertApiController {
    private final ConcertService concertService;

    @Operation(summary = "공연 목록 조회")
    @GetMapping
    public ResponseEntity<List<ConcertResponse>> list(ConcertSearchCondition condition){
        return ResponseEntity.ok(concertService.search(condition));
    }

    @Operation(summary = "공연 생성", description = "인증 필요")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping
    public ResponseEntity<ConcertResponse> create(@RequestBody ConcertCreateRequest req){
        return ResponseEntity.ok(concertService.create(req));
    }

    @Operation(summary = "공연 단건 조회")
    @GetMapping("/{id}")
    public ResponseEntity<ConcertResponse> findOne(@PathVariable Long id){
        return ResponseEntity.ok(concertService.findById(id));
    }
}
