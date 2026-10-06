package io.github.doantrantuandat.example.accounts;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.TimeZone;

@SpringBootApplication
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
}
