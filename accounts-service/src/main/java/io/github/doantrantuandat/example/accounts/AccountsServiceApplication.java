package io.github.doantrantuandat.example.accounts;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Collections;
import java.util.TimeZone;

import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.service.Contact;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;

@SpringBootApplication
@EnableSwagger2
public class AccountsServiceApplication {

    public static void main(String[] args) {
        // pgjdbc sends the JVM's default TimeZone as a literal connection-startup packet param that no
        // JDBC URL option can override - force UTC so this never depends on host locale/tzdata.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(AccountsServiceApplication.class, args);
    }

    // @Bean, not @Component on OrderLinkerModule itself - see that class's own comment (ASM trap).
    // @Value on the factory-method parameter resolves the property without Spring needing to
    // introspect OrderLinkerModule's own class file via component-scan.
    @Bean
    public OrderLinkerModule orderLinkerModule(@Value("${ordering.service.url}") String orderingServiceUrl) {
        return new OrderLinkerModule(orderingServiceUrl);
    }

    @Bean
    public Docket api() {
        return new Docket(DocumentationType.SWAGGER_2)
            .select()
            .apis(RequestHandlerSelectors.any())
            .paths(PathSelectors.any())
            .build()
            .apiInfo(new ApiInfo(
                "Accounts Service API",
                "JSON:API for accounts — crnk-framework-lts 4.0.0-lts.2",
                "1.0", null,
                new Contact("Doan Trung Duc Dat", "https://github.com/doantrantuandat", ""),
                null, null, Collections.emptyList()));
    }
}
