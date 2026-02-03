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
import studying.blog.dto.ArticleViewResponse;
import studying.blog.dto.UpdateArticleRequest;
import studying.blog.repository.ArticleLikeRepository;
import studying.blog.repository.UserRepository;
import studying.blog.service.BlogService;
import studying.blog.service.LikeService;
import studying.blog.domain.User;

import java.security.Principal;
import java.util.List;

@RequiredArgsConstructor
@RestController
public class BlogApiController {
    private final BlogService blogService;
    private final LikeService likeService;
    private final UserRepository userRepository;
    private final ArticleLikeRepository articleLikeRepository;

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
    public ResponseEntity<ArticleViewResponse>findArticle(@PathVariable long id,Principal principal){
        Article article = blogService.getArticle(id);

        boolean liked = false;

        if(principal != null){
            String email = principal.getName();
            User user = userRepository.findByEmail(email).orElseThrow(() -> new IllegalArgumentException("not found user: " + email));
            liked = articleLikeRepository.existsByUserIdAndArticleId(user.getId(),article.getId());
        }
        return ResponseEntity.ok(new ArticleViewResponse(article,liked));
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
