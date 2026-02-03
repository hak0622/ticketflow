package studying.blog.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.domain.Article;
import studying.blog.domain.User;
import studying.blog.dto.AddArticleRequest;
import studying.blog.dto.ArticleSearchCondition;
import studying.blog.dto.ArticleViewResponse;
import studying.blog.dto.UpdateArticleRequest;
import studying.blog.repository.ArticleLikeRepository;
import studying.blog.repository.BlogRepository;
import studying.blog.repository.UserRepository;

import java.util.List;

@RequiredArgsConstructor
@Service
public class BlogService {
    private final BlogRepository blogRepository;
    private final UserRepository userRepository;
    private final ArticleLikeRepository articleLikeRepository;

    public Article save(AddArticleRequest request,String userName){
        User user = userRepository.findByEmail(userName).orElseThrow(() -> new IllegalArgumentException("not found: " + userName));
        return blogRepository.save(request.toEntity(user));
    }

    public Page<Article> findAll(Pageable pageable){
        return blogRepository.findAll(pageable);
    }

    public Page<Article> search(ArticleSearchCondition condition, Pageable pageable) {
        return blogRepository.search(condition, pageable);
    }

    //수정,삭제,내부 검증용으로 사용(조회수 증가 없음)
    public Article getArticle(Long id){
        return blogRepository.findById(id).orElseThrow(()->new IllegalArgumentException("not found : " + id));
    }

    //상세 조회 전용(조회수 증가 포함)
    @Transactional
    public Article viewArticle(Long id){
        int updated = blogRepository.incrementViewCount(id);
        if(updated == 0){
            throw new IllegalArgumentException("not found : " + id);
        }
        return blogRepository.findById(id).orElseThrow(()->new IllegalArgumentException("not found : " + id));
    }

    public void delete(Long id){
        Article article = getArticle(id);
        authorizeArticleAuthor(article);
        blogRepository.delete(article);
    }

    @Transactional
    public Article update(long id, UpdateArticleRequest request){
        Article article = getArticle(id);
        authorizeArticleAuthor(article);
        article.update(request.getTitle(),request.getContent());
        return article;
    }

    @Transactional(readOnly = true)
    public ArticleViewResponse getArticleViewResponse(Long articleId){
        Article article = getArticle(articleId);

        boolean liked = false;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if(auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())){
            String userEmail = auth.getName();

            User user = userRepository.findByEmail(userEmail).orElseThrow(() -> new IllegalArgumentException("not found : " + userEmail));

            liked = articleLikeRepository.existsByUserIdAndArticleId(user.getId(), articleId);
        }
        return new ArticleViewResponse(article, liked);
    }

    private static void authorizeArticleAuthor(Article article){
        String userName = SecurityContextHolder.getContext().getAuthentication().getName();

        if(!article.getAuthor().getEmail().equals(userName)){
            throw new IllegalArgumentException("not authorized");
        }
    }
}
