package studying.blog.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import studying.blog.dto.LectureCreateRequest;
import studying.blog.dto.LectureResponse;
import studying.blog.service.LectureService;

@Controller
@RequiredArgsConstructor
@RequestMapping("/api/lectures")
public class LectureApiController {
    private final LectureService lectureService;

    @PostMapping
    public ResponseEntity<LectureResponse> create(@RequestBody LectureCreateRequest req){
        return ResponseEntity.ok(lectureService.create(req));
    }

    @GetMapping
    public ResponseEntity<LectureResponse>findOne(@PathVariable Long id){
        return ResponseEntity.ok(lectureService.findById(id));
    }
}
