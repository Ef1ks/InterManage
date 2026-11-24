package com.intermanage.backend.auth.config;

import jakarta.servlet.DispatcherType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CsrfFilter;

import com.intermanage.backend.activity.component.StoreEventBuffer;
import com.intermanage.backend.activity.component.StoreEventFilter;
import com.intermanage.backend.auth.security.AppUserDetailsService;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.auth.security.AutoLogout;
import com.intermanage.backend.auth.security.BackgroundAccounts;
import com.intermanage.backend.auth.security.IdleLock;
import com.intermanage.backend.auth.security.JwtAuthenticationFilter;
import com.intermanage.backend.auth.security.ProblemAccessDeniedHandler;
import com.intermanage.backend.auth.security.RateLimitFilter;
import com.intermanage.backend.auth.security.RequestLimits;
import com.intermanage.backend.auth.security.WorkdayLock;
import com.intermanage.backend.auth.security.JwtService;

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
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService,
            BackgroundAccounts backgroundAccounts, WorkdayLock workdayLock, IdleLock idleLock,
            AutoLogout autoLogout, RequestLimits requestLimits, StoreEventBuffer storeEvents) throws Exception {
        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Czytniki kart NFC nie mają sesji ani ciasteczek: przedstawiają się kluczem (TimeClockController).
                // Aplikacja mobilna wysyła token w nagłówku Authorization, którego przeglądarka sama nie dołącza
                // (a bez CORS strona z innego originu nie może go ustawić), więc CSRF jej nie dotyczy.
                .csrf(csrf -> csrf.spa().ignoringRequestMatchers("/api/time-clock/**", "/api/store-agent/**",
                                "/api/auth/mobile/login")
                        .ignoringRequestMatchers(request -> JwtService.bearerToken(request) != null))
                .authorizeHttpRequests(auth -> auth
                        // Nieobsłużony wyjątek idzie do /error drugim przejściem (ERROR), w którym filtr JWT nie
                        // działa, więc bez tego błąd serwera wracał jako 401, a front przy 401 wylogowuje.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // Koniec strumienia na żywo (SseEmitter) i long pollingu agenta (DeferredResult) to drugie
                        // przejście (ASYNC) bez filtra JWT. Samo żądanie przeszło już sprawdzenie; bez tego domknięcie
                        // strumienia (np. o 17:00, gdy konta idą do tła) kończyło się odmową na wysłanej już odpowiedzi
                        // („response is already committed”).
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/api/auth/csrf", "/api/auth/login", "/api/auth/mobile/login",
                                "/api/auth/activate").permitAll()
                        // Konta zalogowane w tle: lista i przejście na konto (PIN-em) działają też na stronie
                        // logowania, gdy żadne konto nie jest aktywne.
                        .requestMatchers(HttpMethod.GET, "/api/auth/accounts").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/accounts/*/switch", "/api/auth/accounts/logout")
                        .permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/api/auth/accounts/*").permitAll()
                        .requestMatchers("/api/time-clock/**").permitAll()
                        // Agent sklepu przedstawia się własnym kluczem (StoreAgentApiController).
                        .requestMatchers("/api/store-agent/**").permitAll()
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
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler(new ProblemAccessDeniedHandler()))
                // Limity zapytań przed CSRF i JWT, żeby żądania ponad limit nie sięgały do bazy.
                .addFilterBefore(new RateLimitFilter(requestLimits), CsrfFilter.class)
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, backgroundAccounts, workdayLock, idleLock,
                                autoLogout),
                        AnonymousAuthenticationFilter.class)
                // Dziennik sklepu: po JWT, żeby znać osobę, i przed autoryzacją, żeby widzieć też odmowy.
                .addFilterAfter(new StoreEventFilter(storeEvents), JwtAuthenticationFilter.class);

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
