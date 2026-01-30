package studying.blog.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import studying.blog.domain.Article;
import studying.blog.domain.User;
import studying.blog.repository.BlogRepository;
import studying.blog.repository.UserRepository;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class BlogServiceTest {

    @Autowired private BlogService blogService;
    @Autowired private BlogRepository blogRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager em;


    //테스트마다 DB 정리
    @AfterEach
    void tearDown(){
        blogRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("조회수 단건 증가가 정상 동작한다.")
    void viewCount_increase_once(){
        //given
        User user = userRepository.save(
                User.builder()
                        .email("test@test.com")
                        .password("pw")
                        .nickname("nick")
                        .build()
        );

        Article article = blogRepository.save(
                Article.builder()
                        .author(user)
                        .title("title")
                        .content("content")
                        .build()
        );

        Long articleId = article.getId();

        //when
        blogService.viewArticle(articleId); //조회수 1 증가

        em.clear();
        Article updated = blogRepository.findById(articleId).orElseThrow();

        assertThat(updated.getViewCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("조회수 동시성 테스트")
    void viewCount_concurrency()throws Exception{
        User user = userRepository.save(
                User.builder()
                        .email("test@test.com")
                        .password("pw")
                        .nickname("nick")
                        .build()
        );

        Article article = blogRepository.save(
                Article.builder()
                        .author(user)
                        .title("title")
                        .content("content")
                        .build()
        );

        Long articleId = article.getId();

        int threadCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for(int i=0; i<threadCount; i++){
            executorService.submit(()->{
                try{
                    blogService.viewArticle(articleId);
                }finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(10, TimeUnit.SECONDS);
        executorService.shutdown();

        assertThat(finished).isTrue();

        em.clear();
        Article updated = blogRepository.findById(articleId).orElseThrow();
        assertThat(updated.getViewCount()).isEqualTo(threadCount);
    }
}