package com.intermanage.backend.deliorder.dto;

import java.util.List;

/** Działy do wyboru przy wydruku zamówienia: z pozycji zamówienia i z całego formularza. */
public record DeliOrderPrintSections(List<String> order, List<String> form) {
}
