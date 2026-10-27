package com.intermanage.backend.deliorder.dto;

import java.util.List;

/** Cały formularz zamówienia garmażu sklepu: działy (zakładki) w kolejności wyświetlania. */
public record OrderFormResponse(List<OrderSectionResponse> sections) {
}
