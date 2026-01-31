package studying.blog.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import studying.blog.domain.Article;
import studying.blog.dto.AddArticleRequest;
import studying.blog.dto.ArticleResponse;
import studying.blog.dto.UpdateArticleRequest;
import studying.blog.service.BlogService;
import studying.blog.service.LikeService;

import java.security.Principal;
import java.util.List;

@RequiredArgsConstructor
@RestController
public class BlogApiController {
    private final BlogService blogService;
    private final LikeService likeService;

    @PostMapping("/api/articles")
    public ResponseEntity<ArticleResponse> addArticle(@RequestBody AddArticleRequest request, Principal principal){
        Article savedArticle = blogService.save(request, principal.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(new ArticleResponse(savedArticle));
    }

    @GetMapping("/api/articles")
    public ResponseEntity<List<ArticleResponse>>findAllArticles(
            @PageableDefault(size = 5,sort="id",direction = Sort.Direction.DESC) Pageable pageable){
        List<ArticleResponse> articles = blogService.findAll(pageable)
                .stream()
                .map(ArticleResponse::new)
                .toList();

        return ResponseEntity.ok().body(articles);
    }

    @GetMapping("/api/articles/{id}")
    public ResponseEntity<ArticleResponse>findArticle(@PathVariable long id){
        Article article = blogService.viewArticle(id);
        return ResponseEntity.ok().body(new ArticleResponse(article));
    }

    @DeleteMapping("/api/articles/{id}")
    public ResponseEntity<Void>deleteArticle(@PathVariable long id){
        blogService.delete(id);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/api/articles/{id}")
    public ResponseEntity<ArticleResponse>updateArticle(@PathVariable long id, @RequestBody UpdateArticleRequest request){
        Article updatedArticle = blogService.update(id, request);
        return ResponseEntity.ok().body(new ArticleResponse(updatedArticle));
    }

    @PostMapping("/api/articles/{id}/like")
    public ResponseEntity<LikeResponse>toggleLike(@PathVariable Long id,Principal principal){
        LikeService.LikeToggleResult result = likeService.toggleLike(id, principal.getName());
        return ResponseEntity.ok(new LikeResponse(result.liked(),result.likeCount()));
    }

    public record LikeResponse(boolean liked, long likeCount){}
}
