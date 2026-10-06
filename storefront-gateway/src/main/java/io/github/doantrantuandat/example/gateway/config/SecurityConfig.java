package io.github.doantrantuandat.example.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Real Spring Security (not the MockAuth header-check pattern used by the other 3 services) -
 * deliberately different, to prove crnk-adjacent code coexists cleanly with a standard Spring Security
 * filter chain too. Verified against Spring Security 7.1.1 (as managed by spring-boot-starter-parent
 * 4.1.1): the lambda-DSL builder methods below (authorizeHttpRequests/httpBasic/csrf, all taking a
 * Customizer) are unchanged from Boot 3's shape - confirmed by decompiling the actual resolved jar
 * rather than assuming.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager(
            User.withUsername("admin").password("{noop}demo-password-not-for-real-use").roles("ADMIN").build()
        );
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/orders").authenticated()
                .anyRequest().permitAll())
            .httpBasic(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable()); // stateless demo API, no browser form posts
        return http.build();
    }
}
