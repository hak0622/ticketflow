package studying.blog;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableJpaAuditing
@EnableScheduling
@EntityScan("studying.blog.domain")
@EnableJpaRepositories("studying.blog.repository")
@ComponentScan(
    basePackages = "studying.blog",
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "studying\\.blog\\.experiments\\..*"
    )
)
public class BlogApplication {
    @PostConstruct
    public void started() {
        TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Seoul"));
    }

	public static void main(String[] args) {
		SpringApplication.run(BlogApplication.class, args);
	}
}
