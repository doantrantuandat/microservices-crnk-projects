package io.github.doantrantuandat.example.catalog;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI catalogOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Catalog Service API")
                .description("JSON:API for products with crnk-framework-lts 4.0.0-lts.2")
                .version("1.0"));
    }
}
