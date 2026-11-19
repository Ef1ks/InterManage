package com.intermanage.backend.activity.component;

import java.util.Map;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;

import com.intermanage.backend.activity.entity.StoreEventKind;
import com.intermanage.backend.activity.entity.StoreEventLevel;

/**
 * Ostrzeżenia i błędy z logów backendu (SLF4J, {@code log.warn}, {@code log.error}) w dzienniku sklepu. Sklep, osoba i
 * moduł są z MDC, które ustawia {@link StoreEventFilter} na czas żądania; log z zadania w tle (bez sklepu) trafia do
 * dziennika firmy, który widzi właściciel. Tylko loggery aplikacji, bez własnego pakietu, żeby błąd zapisu dziennika
 * się nie zapętlił.
 */
class StoreEventLogAppender extends AppenderBase<ILoggingEvent> {

    private static final String APP_PACKAGE = "com.intermanage.backend.";
    private static final String OWN_PACKAGE = "com.intermanage.backend.activity.";

    private final StoreEventBuffer buffer;

    StoreEventLogAppender(StoreEventBuffer buffer) {
        this.buffer = buffer;
        setName("STORE_EVENTS");
    }

    @Override
    protected void append(ILoggingEvent event) {
        String logger = event.getLoggerName();
        if (!event.getLevel().isGreaterOrEqual(Level.WARN) || logger == null || !logger.startsWith(APP_PACKAGE)
                || logger.startsWith(OWN_PACKAGE)) {
            return;
        }
        Map<String, String> mdc = event.getMDCPropertyMap();
        String module = mdc.get(StoreEventFilter.MDC_MODULE);
        StringBuilder detail = new StringBuilder(logger.substring(APP_PACKAGE.length()));
        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            detail.append('\n').append(ThrowableProxyUtil.asString(throwable));
        }
        buffer.record(StoreEventRecord.now(uuid(mdc.get(StoreEventFilter.MDC_STORE)),
                uuid(mdc.get(StoreEventFilter.MDC_USER)), StoreEventKind.SYSTEM,
                event.getLevel().isGreaterOrEqual(Level.ERROR) ? StoreEventLevel.ERROR : StoreEventLevel.WARN,
                module != null ? module : "system", null, null, null, null, event.getFormattedMessage(),
                detail.toString()));
    }

    private static UUID uuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
