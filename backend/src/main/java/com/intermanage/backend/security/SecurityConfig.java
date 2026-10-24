package com.intermanage.backend.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.user.AppUserRepository;

/**
 * Bezstanowe uwierzytelnianie JWT: token w ciasteczku HttpOnly {@value JwtService#COOKIE_NAME} + CSRF w trybie SPA
 * (ciasteczko XSRF-TOKEN, frontend odsyła je w nagłówku X-XSRF-TOKEN). CSRF zostaje, bo przeglądarka dołącza
 * ciasteczko z tokenem automatycznie. Frontend i API są serwowane z jednego originu (w dev przez proxy Vite),
 * więc CORS nie jest potrzebny.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService, AppUserRepository users,
            PlatformTransactionManager transactionManager) throws Exception {
        TransactionTemplate readOnlyTx = new TransactionTemplate(transactionManager);
        readOnlyTx.setReadOnly(true);

        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(CsrfConfigurer::spa)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/csrf", "/api/auth/login", "/api/auth/activate").permitAll()
                        // Jedyne endpointy dostępne, zanim użytkownik ustawi własne hasło.
                        .requestMatchers("/api/auth/me", "/api/auth/password/setup").authenticated()
                        .requestMatchers("/api/settings/**").hasAuthority(AppUserPrincipal.OWNER_AUTHORITY)
                        .anyRequest().access(AuthorizationManagers.allOf(
                                AuthenticatedAuthorizationManager.authenticated(),
                                AuthorizationManagers.not(
                                        AuthorityAuthorizationManager.hasAuthority(
                                                AppUserPrincipal.PASSWORD_SETUP_AUTHORITY)))))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .addLogoutHandler((request, response, authentication) -> jwtService.clearTokenCookie(response))
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, users, readOnlyTx),
                        AnonymousAuthenticationFilter.class);

        return http.build();
    }

    /** Sprawdza login i hasło w {@code POST /api/auth/login}. */
    @Bean
    AuthenticationManager authenticationManager(AppUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
