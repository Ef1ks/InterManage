package com.intermanage.backend.sales.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.sales.dto.SupplierTurnoverReport;
import com.intermanage.backend.sales.dto.TurnoverSupplier;
import com.intermanage.backend.sales.service.SupplierTurnoverService;

/** Obroty z dostawcami (kafelek w Sprzedaży i raportach). */
@RestController
@RequestMapping("/api/stores/{storeId}/reports/supplier-turnover")
@PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
public class SupplierTurnoverController {

    private final SupplierTurnoverService supplierTurnoverService;

    public SupplierTurnoverController(SupplierTurnoverService supplierTurnoverService) {
        this.supplierTurnoverService = supplierTurnoverService;
    }

    /** Dostawcy z przyjęć PZ sklepu, od ostatniej dostawy. */
    @GetMapping("/suppliers")
    public List<TurnoverSupplier> suppliers(@PathVariable UUID storeId) {
        return supplierTurnoverService.suppliers(storeId);
    }

    /** Zakup netto od dostawcy {@code supplier} (kod) w okresie (oba końce włącznie) po miesiącach i nomenklaturze. */
    @GetMapping
    public SupplierTurnoverReport report(@PathVariable UUID storeId, @RequestParam String supplier,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return supplierTurnoverService.report(storeId, supplier, from, to);
    }
}
