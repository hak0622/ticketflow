package studying.blog.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import studying.blog.domain.Article;
import studying.blog.dto.ArticleListViewResponse;
import studying.blog.dto.ArticleSearchCondition;
import studying.blog.dto.ArticleViewResponse;
import studying.blog.service.BlogService;

import java.util.List;

@RequiredArgsConstructor
@Controller
public class BlogViewController {
    private final BlogService blogService;

    @GetMapping("/articles")
    public String getArticles(
            @PageableDefault(size = 5, sort = "id", direction = Sort.Direction.DESC) Pageable pageable,
            @ModelAttribute ArticleSearchCondition condition,
            Model model){

//        Page<Article> articlePage = blogService.findAll(pageable);
        Page<Article> articlePage = blogService.search(condition, pageable);


        List<ArticleListViewResponse> articles =
                articlePage.getContent()
                        .stream()
                        .map(ArticleListViewResponse::new).toList();

        model.addAttribute("articles",articles);
        model.addAttribute("currentPage", articlePage.getNumber()); // 현재 페이지 (0부터 시작)
        model.addAttribute("totalPages", articlePage.getTotalPages()); // 전체 페이지 수
        model.addAttribute("hasPrevious", articlePage.hasPrevious()); // 이전 버튼 유무
        model.addAttribute("hasNext", articlePage.hasNext()); // 다음 버튼 유무

        model.addAttribute("condition", condition); //검색어를 화면에서 유지하려고 같이 넘김
        return "articleList";
    }

    @GetMapping("/articles/{id}")
    public String getArticle(@PathVariable Long id, Model model) {
        Article article = blogService.findById(id);

        String loginUserEmail =
                SecurityContextHolder.getContext().getAuthentication().getName();
        System.out.println("loginUserEmail = " + loginUserEmail);

        boolean isAuthor =
                article.getAuthor().getEmail().equals(loginUserEmail);

        model.addAttribute("article", new ArticleViewResponse(article));
        model.addAttribute("isAuthor", isAuthor); // ⭐ 추가

        return "article";
    }

    @GetMapping("/new-article")
    public String newArticle(@RequestParam(required = false) Long id,Model model){
        if(id == null){
            model.addAttribute("article",new ArticleViewResponse());
        }else{
            Article article = blogService.findById(id);
            model.addAttribute("article",new ArticleViewResponse(article));
        }
        return "newArticle";
    }
}
