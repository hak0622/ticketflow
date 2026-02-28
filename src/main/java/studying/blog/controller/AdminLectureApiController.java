package studying.blog.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import studying.blog.dto.EnrollmentAdminResponse;
import studying.blog.dto.LectureAdminUpsertRequest;
import studying.blog.dto.LectureResponse;
import studying.blog.service.LectureService;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/lectures")
public class AdminLectureApiController {
    private final LectureService lectureService;

    @PostMapping
    public ResponseEntity<LectureResponse>create(@RequestBody LectureAdminUpsertRequest req){
        return ResponseEntity.ok(lectureService.adminCreate(req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<LectureResponse>update(@PathVariable Long id, @RequestBody LectureAdminUpsertRequest req){
        return ResponseEntity.ok(lectureService.adminUpdate(id,req));
    }

    @PatchMapping("/{id}/close")
    public ResponseEntity<LectureResponse>close(@PathVariable Long id){
        return ResponseEntity.ok(lectureService.adminClose(id));
    }

    @GetMapping("/{id}/enrollments")
    public ResponseEntity<List<EnrollmentAdminResponse>> enrollments(@PathVariable Long id){
        return ResponseEntity.ok(lectureService.adminEnrollments(id));
    }

    @GetMapping
    public ResponseEntity<List<LectureResponse>>list(){
        return ResponseEntity.ok(lectureService.findAll());
    }
}
