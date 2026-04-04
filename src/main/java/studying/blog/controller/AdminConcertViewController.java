package studying.blog.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class AdminConcertViewController {
    @GetMapping("/admin/concerts")
    public String adminConcertsPage(){
        return "admin/concerts";
    }
}
