package com.intermanage.backend.sales.dto;

import java.util.List;
import java.util.UUID;

/** Dostawca do kontroli strat z kolorem z palety i ITM8 podpiętych artykułów (od najwcześniej podpiętego). */
public record LossControlGroupResponse(UUID id, String name, String color, List<String> itm8s) {
}
