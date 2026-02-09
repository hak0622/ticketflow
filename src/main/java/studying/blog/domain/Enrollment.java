package studying.blog.domain;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
@Table(
        name = "enrollment",
        uniqueConstraints = {@UniqueConstraint(name = "uk_enrollment_lecture_user",columnNames = {"lecture_id","user_key"})},
        indexes = {@Index(name = "idx_enrollment_lecture",columnList = "lecture_id"),@Index(name = "idx_enrollment_user",columnList = "user_key")}
)
public class Enrollment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lecture_id",nullable = false)
    private Lecture lecture;

    @Column(name = "user_key",nullable = false,length = 64)
    private String userKey;

    @Column(name = "created_at",nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist(){
        this.createdAt = LocalDateTime.now();
    }

    public static Enrollment create(Lecture lecture,String userKey){
        return Enrollment.builder()
                .lecture(lecture)
                .userKey(userKey)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
