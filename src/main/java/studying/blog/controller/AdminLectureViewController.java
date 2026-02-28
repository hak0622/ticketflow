package studying.blog.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class AdminLectureViewController {
    @GetMapping("/admin/lectures")
    public String adminLecturesPage(){
        return "admin/lectures";
    }
}
