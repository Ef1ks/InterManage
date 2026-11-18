package com.intermanage.backend.sales.dto;

import java.util.List;
import java.util.UUID;

/**
 * Dostawca do kontroli strat z kolorem z palety, ITM8 podpiętych artykułów (od najwcześniej podpiętego) i podpiętym
 * dostawcą z listy dostawców sklepu (puste pola = bez dostawcy).
 */
public record LossControlGroupResponse(UUID id, String name, String color, List<String> itm8s, UUID supplierId,
        String supplierCode, String supplierName) {
}
