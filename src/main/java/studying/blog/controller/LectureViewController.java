package studying.blog.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import studying.blog.service.LectureService;

@Controller
@RequiredArgsConstructor
public class LectureViewController {
    private final LectureService lectureService;

    @GetMapping("/lectures")
    public String lectureLobby(Model model){
        model.addAttribute("lectures",lectureService.findAll());
        return "lecture/lobby";
    }

    @GetMapping("/lectures/{id}")
    public String lectureDetail(@PathVariable Long id, Model model){
        model.addAttribute("lecture",lectureService.findById(id));
        return "lecture/detail";
    }

    @GetMapping("/lectures/{id}/apply")
    public String lectureApply(@PathVariable Long id, Model model){
        model.addAttribute("lecture",lectureService.findById(id));
        return "lecture/apply";
    }
    @GetMapping("/lectures/{id}/receipt")
    public String receipt(@PathVariable Long id, Model model){
        model.addAttribute("lecture", lectureService.findById(id));
        return "lecture/receipt";
    }
}
