package com.intermanage.backend.auth.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

/**
 * Odmowa jako ProblemDetail. Odrzucony token CSRF ma {@code "csrf": true}: frontend pobiera wtedy nowy token i
 * ponawia żądanie (np. kolejny kawałek długiej wysyłki raportu), zamiast kończyć pracę komunikatem „Brak uprawnień”.
 * Odmowa CSRF zapada przed filtrem dziennika sklepu, więc trafia do logów (i do dziennika firmy).
 */
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemAccessDeniedHandler.class);

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        boolean csrf = exception instanceof CsrfException;
        if (csrf) {
            log.warn("Odrzucony token CSRF: {} {} ({})", request.getMethod(), request.getRequestURI(),
                    exception.getMessage());
        }
        String detail = csrf ? "Token bezpieczeństwa wygasł. Odśwież stronę i spróbuj ponownie."
                : "Brak uprawnień do tej operacji.";
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,\"detail\":\""
                + detail + "\",\"csrf\":" + csrf + "}");
    }
}
