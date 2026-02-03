package studying.blog.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;
import studying.blog.domain.Article;
import studying.blog.domain.User;
import studying.blog.repository.ArticleLikeRepository;
import studying.blog.repository.BlogRepository;
import studying.blog.repository.UserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@SpringBootTest
class LikeServiceTest {

    @Autowired LikeService likeService;
    @Autowired BlogRepository blogRepository;
    @Autowired ArticleLikeRepository likeRepository;
    @Autowired UserRepository userRepository;

    @PersistenceContext EntityManager em;

    @Autowired TransactionTemplate tx;

    Long articleId;
    List<String> userEmails;

    @BeforeEach
    void setUp() {
        tx.execute(status -> {
            String uniq = String.valueOf(System.nanoTime());

            User author = userRepository.save(User.builder()
                    .email("author-" + uniq + "@test.com")
                    .password("pw")
                    .nickname("author-" + uniq)
                    .build());

            Article article = blogRepository.save(Article.builder()
                    .author(author)
                    .title("title")
                    .content("content")
                    .build());

            articleId = article.getId();

            int users = 100;
            userEmails = new ArrayList<>(users);

            for (int i = 0; i < users; i++) {
                String email = "u-" + uniq + "-" + i + "@test.com";
                userRepository.save(User.builder()
                        .email(email)
                        .password("pw")
                        .nickname("u-" + uniq + "-" + i)
                        .build());
                userEmails.add(email);
            }

            em.flush();
            em.clear();
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        tx.execute(status -> {
            likeRepository.deleteAllInBatch();
            blogRepository.deleteAllInBatch();
            userRepository.deleteAllInBatch();
            em.clear();
            return null;
        });
    }


    //동시에 여러 명이 좋아요 누르는 테스트
    @Test
    void manyUsers_likeConcurrently() throws Exception {
        int threads = userEmails.size();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        var errors = java.util.Collections.synchronizedList(new java.util.ArrayList<Throwable>());

        for (String email : userEmails) {
            pool.submit(() -> {
                try {
                    start.await();
                    likeService.toggleLike(articleId, email);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(finished).isTrue();

        if (!errors.isEmpty()) {
            errors.get(0).printStackTrace();
        }
        assertThat(errors.size()).isZero();


        em.clear();
        long likeCount = blogRepository.getLikeCount(articleId);
        long likeRows = likeRepository.countByArticleId(articleId);

        assertThat(likeCount).isEqualTo(threads);
        assertThat(likeRows).isEqualTo(threads);
    }

    //같은 유저가 좋아요를 연타로 눌렀을 때
    @Test
    void sameUserToggleConcurrently() throws Exception {
        String uniq = String.valueOf(System.nanoTime());
        String email = "solo-" + uniq + "@test.com";

        tx.execute(status -> {
            userRepository.save(User.builder()
                    .email(email)
                    .password("pw")
                    .nickname("solo-" + uniq)
                    .build());
            em.flush();
            em.clear();
            return null;
        });

        int threads = 100;

        ExecutorService pool = Executors.newFixedThreadPool(20);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        var errors = java.util.Collections.synchronizedList(new java.util.ArrayList<Throwable>());

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    likeService.toggleLike(articleId, email);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(finished).isTrue();

        if (!errors.isEmpty()) {
            errors.get(0).printStackTrace();
        }
        assertThat(errors.size()).isZero();


        em.clear();

        long likeCount = blogRepository.getLikeCount(articleId);
        long likeRows = likeRepository.countByArticleId(articleId);

        // 토글 연타의 정상 결과: 0 또는 1
        assertThat(likeCount).isBetween(0L, 1L);
        assertThat(likeRows).isBetween(0L, 1L);

        // DB 정합성: row 수와 카운트가 같아야 함
        assertThat(likeCount).isEqualTo(likeRows);
    }
}