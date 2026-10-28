package com.intermanage.backend.nomenclature.dto;

import com.intermanage.backend.nomenclature.entity.NomenclatureLevel;

/** Pozycja nomenklatury sklepu: kod bez spacji ("01", "0101", "010101"), nazwa i poziom. */
public record NomenclatureOption(String code, String name, NomenclatureLevel level) {
}
