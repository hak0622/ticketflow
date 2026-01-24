package studying.blog.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.domain.Article;
import studying.blog.domain.User;
import studying.blog.dto.AddArticleRequest;
import studying.blog.dto.UpdateArticleRequest;
import studying.blog.repository.BlogRepository;
import studying.blog.repository.UserRepository;

import java.util.List;

@RequiredArgsConstructor
@Service
public class BlogService {
    private final BlogRepository blogRepository;
    private final UserRepository userRepository;

    public Article save(AddArticleRequest request,String userName){
        User user = userRepository.findByEmail(userName).orElseThrow(() -> new IllegalArgumentException("not found: " + userName));
        return blogRepository.save(request.toEntity(user));
    }

    public List<Article> findAll(){
        return blogRepository.findAll();
    }

    public Article findById(Long id){
        return blogRepository.findById(id).orElseThrow(()-> new IllegalArgumentException("not found: " + id));
    }

    public void delete(Long id){
        Article article = blogRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("not found : " + id));
        authorizeArticleAuthor(article);
        blogRepository.delete(article);
    }

    @Transactional
    public Article update(long id, UpdateArticleRequest request){
        Article article = blogRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("not found : " + id));
        authorizeArticleAuthor(article);
        article.update(request.getTitle(),request.getContent());
        return article;
    }

    private static void authorizeArticleAuthor(Article article){
        String userName = SecurityContextHolder.getContext().getAuthentication().getName();

        if(!article.getAuthor().getEmail().equals(userName)){
            throw new IllegalArgumentException("not authorized");
        }
    }
}
