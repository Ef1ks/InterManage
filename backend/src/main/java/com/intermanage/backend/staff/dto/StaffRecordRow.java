package com.intermanage.backend.staff.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.intermanage.backend.staff.component.StaffCompliance.Severity;
import com.intermanage.backend.staff.entity.ContractKind;
import com.intermanage.backend.staff.entity.EmploymentType;

/**
 * Pracownik na liście Kadr. Pola umowy puste, gdy nie ma obowiązującej; etat dzisiejszy, z aneksami;
 * {@code remainingLeave} tylko przy umowie o pracę. Sklep i dział do filtrów listy (lista startuje od bieżącego sklepu).
 */
public record StaffRecordRow(UUID employeeId, String displayName, UUID storeId, String storeNumber, String storeName,
        UUID departmentId, String departmentName, String positionName,
        Instant endedAt, ContractKind contractKind, EmploymentType employmentType, LocalDate contractEnd,
        Integer fteNumerator, Integer fteDenominator, Integer remainingLeave, int warningCount, Severity worstWarning) {
}
