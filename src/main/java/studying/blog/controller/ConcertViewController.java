package studying.blog.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import studying.blog.service.ConcertService;

@Controller
@RequiredArgsConstructor
public class ConcertViewController {
    private final ConcertService concertService;

    @GetMapping("/concerts")
    public String concertLobby(Model model){
        model.addAttribute("concerts", concertService.findAll());
        return "concert/lobby";
    }

    @GetMapping("/concerts/{id}")
    public String concertDetail(@PathVariable Long id, Model model){
        model.addAttribute("concert", concertService.findById(id));
        return "concert/detail";
    }

    @GetMapping("/concerts/{id}/apply")
    public String concertApply(@PathVariable Long id, Model model){
        model.addAttribute("concert", concertService.findById(id));
        return "concert/apply";
    }

    @GetMapping("/concerts/{id}/receipt")
    public String receipt(@PathVariable Long id, Model model){
        model.addAttribute("concert", concertService.findById(id));
        return "concert/receipt";
    }

    @GetMapping("/me")
    public String me(){
        return "concert/me";
    }
}
