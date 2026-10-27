package com.intermanage.backend.document.dto;

import java.time.LocalDate;
import java.util.UUID;

/** Zamówienie garmażu, które jedzie na danym dokumencie ruchu międzymagazynowego (widok dokumentu). */
public record LinkedDeliOrder(UUID orderId, String storeNumber, String storeName, LocalDate deliveryDate) {
}
