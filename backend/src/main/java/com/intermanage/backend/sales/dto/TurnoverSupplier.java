package com.intermanage.backend.sales.dto;

import java.time.LocalDate;

/** Dostawca do wyboru w obrotach z dostawcami: z przyjęć PZ sklepu, nazwa z listy dostawców, gdy jest. */
public record TurnoverSupplier(String code, String name, String nip, LocalDate lastDelivery) {
}
