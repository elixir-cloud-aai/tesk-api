package uk.ac.ebi.tsc.tesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for Spring Boot App
 *
 * @author Ania Niewielska &lt;aniewielska@ebi.ac.uk&gt;
 */
@EnableScheduling
@SpringBootApplication
public class TeskApplication {

    public static void main(String[] args) {
        SpringApplication.run(TeskApplication.class, args);
    }
}
