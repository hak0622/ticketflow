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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private LectureStatus status = LectureStatus.OPEN;

    public boolean hasSeat(){
        return enrolledCount < capacity;
    }

    public void increaseEnrolled(){
        if(!hasSeat()){
            throw new IllegalArgumentException("정원이 초과되었습니다.");
        }
        this.enrolledCount++;
    }

    public void markSoldOut(){
        this.status = LectureStatus.SOLD_OUT;
    }

    public void close(){
        this.status = LectureStatus.CLOSED;
    }

    public void updateByAdmin(String title, LocalDateTime openAt, int capacity, LectureStatus status){
        this.title = title;
        this.openAt = openAt;
        this.capacity = capacity;
        if(status != null){
            this.status = status;
        }
    }
}
