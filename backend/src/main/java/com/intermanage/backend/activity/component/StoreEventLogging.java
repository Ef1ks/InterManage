package com.intermanage.backend.activity.component;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

/**
 * Podpina {@link StoreEventLogAppender} do głównego loggera logback po starcie kontekstu i odpina przy zamknięciu.
 * Bez logback (inna implementacja SLF4J) nic nie robi.
 */
@Component
public class StoreEventLogging implements SmartInitializingSingleton, DisposableBean {

    private final StoreEventBuffer buffer;
    private StoreEventLogAppender appender;

    public StoreEventLogging(StoreEventBuffer buffer) {
        this.buffer = buffer;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!buffer.enabled() || !(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return;
        }
        appender = new StoreEventLogAppender(buffer);
        appender.setContext(context);
        appender.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
    }

    @Override
    public void destroy() {
        if (appender != null && LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender);
            appender.stop();
        }
    }
}
