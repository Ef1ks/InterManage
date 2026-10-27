package com.intermanage.backend.exception;

import java.util.List;

/** Plik nie pasuje do formatu raportu; {@code errors} to komunikaty dla użytkownika (najwyżej {@value com.intermanage.backend.component.ProductReportParser#MAX_REPORTED_ERRORS}). */
public class InvalidReportException extends Exception {

    private final List<String> errors;
    private final int errorCount;

    public InvalidReportException(String message, List<String> errors, int errorCount) {
        super(message);
        this.errors = errors;
        this.errorCount = errorCount;
    }

    public InvalidReportException(String message) {
        this(message, List.of(), 0);
    }

    public List<String> errors() {
        return errors;
    }

    public int errorCount() {
        return errorCount;
    }
}
