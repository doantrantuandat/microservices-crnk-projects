package io.github.doantrantuandat.example.ordering;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI orderingOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Ordering Service API")
                .description("JSON:API for orders/orderLines with crnk-framework-lts 4.0.0-lts.2")
                .version("1.0"));
    }
}
