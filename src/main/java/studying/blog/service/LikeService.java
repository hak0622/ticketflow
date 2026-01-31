package studying.blog.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.domain.Article;
import studying.blog.domain.ArticleLike;
import studying.blog.domain.User;
import studying.blog.repository.ArticleLikeRepository;
import studying.blog.repository.BlogRepository;
import studying.blog.repository.UserRepository;

@RequiredArgsConstructor
@Service
public class LikeService {

    private final BlogRepository blogRepository;
    private final ArticleLikeRepository likeRepository;
    private final UserRepository userRepository;

    @Transactional
    public LikeToggleResult toggleLike(Long articleId, String userEmail){
        User user = userRepository.findByEmail(userEmail).orElseThrow(() -> new IllegalArgumentException("not found user : " + userEmail));

        int deleted = likeRepository.deleteOne(user.getId(), articleId);
        if(deleted == 1){
            blogRepository.decrementLikeCount(articleId);
            return new LikeToggleResult(false,blogRepository.getLikeCount(articleId));
        }
        try{
            Article article = blogRepository.findById(articleId).orElseThrow(() -> new IllegalArgumentException("not found articleId" + articleId));

            likeRepository.saveAndFlush(new ArticleLike(user,article));
            blogRepository.incrementLikeCount(articleId);

            return new LikeToggleResult(true,blogRepository.getLikeCount(articleId));
        }catch (DataIntegrityViolationException e){
            return new LikeToggleResult(true,blogRepository.getLikeCount(articleId));
        }
    }

    public record LikeToggleResult(boolean liked, long likeCount) {}
}
