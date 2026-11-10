package com.intermanage.backend.pricecheck.dto;

import com.intermanage.backend.pricecheck.dto.LowMarginReport.Line;

/**
 * Ocena artykułu przyjęcia na bonie: {@code line} jak w raporcie marż (odchylenie puste = w normie), {@code cadencier}:
 * artykuł aktywny w cadencier (marża ustalona z góry, raport go nie ocenia), {@code excludedSupplier}: dostawca pomijany
 * w raporcie marż.
 */
public record DeliveryLineHint(Line line, boolean cadencier, boolean excludedSupplier) {
}
