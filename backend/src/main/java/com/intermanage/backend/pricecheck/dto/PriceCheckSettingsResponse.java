package com.intermanage.backend.pricecheck.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Minimalna akceptowana marża towaru z bazy kupionego u innego dostawcy; pusta = nie ustawiono. */
public record PriceCheckSettingsResponse(BigDecimal minMarginPercent, String updatedBy, Instant updatedAt) {
}
