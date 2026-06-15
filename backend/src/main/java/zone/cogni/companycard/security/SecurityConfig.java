package zone.cogni.companycard.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.HttpMethod;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  @Bean
  public BCryptPasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
      .csrf(AbstractHttpConfigurer::disable)
      .cors(cors -> {})
      .sessionManagement(session -> session
        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
      )
      .authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/auth/login", "/api/auth/logout", "/api/auth/register").permitAll()
        .requestMatchers("/api/auth/users/**", "/api/admin/**").hasRole("ADMIN")
        .requestMatchers(HttpMethod.GET,
          "/api/ontology/classes",
          "/api/ontology/form-schema",
          "/api/ontology/instance",
          "/api/ontology/instances",
          "/api/ontology/graph",
          "/api/ontology/concepts",
          "/api/stories/**",
          "/api/esco/**",
          "/api/wikidata/**"
        ).permitAll()
        .anyRequest().authenticated()
      );

    return http.build();
  }
}
