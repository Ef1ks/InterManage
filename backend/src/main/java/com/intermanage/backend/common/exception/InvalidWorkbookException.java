package com.intermanage.backend.common.exception;

/** Plik nie jest poprawnym skoroszytem .xlsx / .xlsm. */
public class InvalidWorkbookException extends Exception {
    public InvalidWorkbookException(String message, Throwable cause) {
        super(message, cause);
    }
}
