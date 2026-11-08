package com.intermanage.backend.staff.component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import com.intermanage.backend.staff.entity.StaffContract;
import com.intermanage.backend.staff.entity.StaffContractChange;
import com.intermanage.backend.staff.entity.StaffDisability;
import com.intermanage.backend.staff.entity.StaffLeave;
import com.intermanage.backend.staff.entity.StaffLeaveOpening;
import com.intermanage.backend.staff.entity.StaffProfile;
import com.intermanage.backend.staff.entity.StaffShift;
import com.intermanage.backend.staff.entity.StaffWorkPeriod;

/** Cała kartoteka jednego pracownika: dane wejściowe obliczeń stażu, urlopu i ostrzeżeń. */
public record StaffFile(StaffProfile profile, List<StaffContract> contracts, List<StaffContractChange> contractChanges,
        List<StaffWorkPeriod> workPeriods, List<StaffDisability> disabilities, List<StaffLeave> leaves,
        StaffLeaveOpening opening, List<StaffShift> shifts) {

    /** Wymiar etatu; 1/2 i 2/4 to ten sam etat tylko po przeliczeniu, nie w zapisie. */
    public record Fte(int numerator, int denominator) {
    }

    /** Etat umowy obowiązujący danego dnia: z ostatniego aneksu do tego dnia, a bez niego z samej umowy. */
    public Fte fteOn(StaffContract contract, LocalDate day) {
        return contractChanges.stream()
                .filter(change -> change.getContract() == contract && !change.getValidFrom().isAfter(day))
                .max(Comparator.comparing(StaffContractChange::getValidFrom))
                .map(change -> new Fte(change.getFteNumerator(), change.getFteDenominator()))
                .orElseGet(() -> new Fte(contract.getFteNumerator(), contract.getFteDenominator()));
    }

    /** Umowy o pracę od najwcześniejszej; urlop i staż z Kodeksu pracy dotyczą tylko ich. */
    public List<StaffContract> employment() {
        return contracts.stream().filter(StaffContract::isEmployment)
                .sorted(Comparator.comparing(StaffContract::getStartDate))
                .toList();
    }

    public StaffContract currentContract(LocalDate day) {
        return contracts.stream().filter(contract -> contract.activeOn(day))
                .max(Comparator.comparing((StaffContract contract) -> contract.isEmployment())
                        .thenComparing(StaffContract::getStartDate))
                .orElse(null);
    }

    public StaffDisability disabilityOn(LocalDate day) {
        return disabilities.stream().filter(disability -> disability.activeOn(day))
                .max(Comparator.comparing(StaffDisability::isSignificant))
                .orElse(null);
    }
}
