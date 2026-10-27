package com.intermanage.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Zadania w tle: automatyczne pobieranie faktur z KSeF ({@code app.ksef.sync-enabled}). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
