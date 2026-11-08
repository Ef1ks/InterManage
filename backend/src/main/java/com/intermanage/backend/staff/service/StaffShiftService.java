package com.intermanage.backend.staff.service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.springframework.stereotype.Service;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.staff.dto.ShiftRequest;
import com.intermanage.backend.staff.entity.StaffShift;
import com.intermanage.backend.staff.entity.StaffShift.Hours;
import com.intermanage.backend.staff.repository.StaffShiftRepository;

/** Zastępuje listę godzin: nowe są dopisywane, a brakujące dostają znacznik. Te same zostają bez zmian. */
@Service
public class StaffShiftService {

    private final StaffShiftRepository shifts;

    public StaffShiftService(StaffShiftRepository shifts) {
        this.shifts = shifts;
    }

    void replace(List<StaffShift> current, List<ShiftRequest> requested, Function<Hours, StaffShift> create,
            AppUser by) {
        Set<Hours> wanted = new LinkedHashSet<>();
        for (ShiftRequest shift : requested) {
            wanted.add(hours(shift));
        }
        List<Hours> kept = new ArrayList<>();
        for (StaffShift shift : current) {
            if (wanted.contains(shift.hours())) {
                kept.add(shift.hours());
            } else {
                shift.markDeleted(by);
            }
        }
        wanted.removeAll(kept);
        shifts.saveAll(wanted.stream().map(create).toList());
    }

    private static Hours hours(ShiftRequest shift) {
        LocalTime start = LocalTime.parse(shift.start());
        LocalTime end = LocalTime.parse(shift.end());
        if (start.equals(end)) {
            throw ApiException.badRequest("Godziny pracy: początek i koniec nie mogą być takie same");
        }
        return new Hours(shift.dayOfWeek(), start, end);
    }
}
