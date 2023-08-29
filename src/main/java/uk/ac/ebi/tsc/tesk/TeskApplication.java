package uk.ac.ebi.tsc.tesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;

/**
 * Entry point for Spring Boot App
 *
 * @author Ania Niewielska <aniewielska@ebi.ac.uk>
 */
@EnableScheduling
@SpringBootApplication
public class TeskApplication {

    public static void main(String[] args) {
        SpringApplication.run(TeskApplication.class, args);
    }

    @Bean
    public OpenAPI springShopOpenAPI() {
         return new OpenAPI()
              .info(new Info().title("SpringShop API")
              .description("Spring shop sample application")
              .version("v0.0.1")
              .license(new License().name("Apache 2.0").url("http://springdoc.org")))
              .externalDocs(new ExternalDocumentation()
              .description("SpringShop Wiki Documentation")
              .url("https://springshop.wiki.github.org/docs"));
   }
}
