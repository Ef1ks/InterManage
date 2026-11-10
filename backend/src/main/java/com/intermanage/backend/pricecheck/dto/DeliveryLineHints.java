package com.intermanage.backend.pricecheck.dto;

import java.math.BigDecimal;
import java.util.List;

/** Ocena artykułów przyjęcia PZ do kolorów na bonie, z progami marży sklepu. */
public record DeliveryLineHints(BigDecimal lowGap, BigDecimal highGap, boolean marginsConfigured,
        List<DeliveryLineHint> lines) {
}
