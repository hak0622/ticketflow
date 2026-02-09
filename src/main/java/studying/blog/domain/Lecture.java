package studying.blog.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Lecture {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private LocalDateTime openAt;

    @Column(nullable = false)
    private int capacity;

    @Column(nullable = false)
    private int enrolledCount;

    public boolean hasSeat(){
        return enrolledCount < capacity;
    }

    public void increaseEnrolled(){
        if(!hasSeat()){
            throw new IllegalArgumentException("정원이 초과되었습니다.");
        }
        this.enrolledCount++;
    }
}
