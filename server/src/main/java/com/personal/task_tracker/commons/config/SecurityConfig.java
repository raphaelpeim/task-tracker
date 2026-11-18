package com.personal.task_tracker.commons.config;

import com.personal.task_tracker.commons.exception.SecurityProblemHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

	@Bean
	public PasswordEncoder encoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http, SecurityProblemHandler problemHandler) {
		http.authorizeHttpRequests(auth ->
						auth
								.requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login")
								.permitAll()
								.anyRequest()
								.authenticated()
				)
				// No CSRF risk: the token travels in a header the browser never sends on its own, unlike a cookie
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session ->
						session
								.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
				)
				.oauth2ResourceServer(oauth2 ->
						oauth2
								.jwt(Customizer.withDefaults())
								.authenticationEntryPoint(problemHandler)
								.accessDeniedHandler(problemHandler)
				);
		return http.build();
	}
}
