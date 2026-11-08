package com.intermanage.backend.staff.controller;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.staff.dto.ScheduleDepartment;
import com.intermanage.backend.staff.dto.TimeBalanceResponse;
import com.intermanage.backend.staff.dto.TimeCardResponse;
import com.intermanage.backend.staff.dto.TimeRequests;
import com.intermanage.backend.staff.dto.TimeSettingsResponse;
import com.intermanage.backend.staff.service.StaffScheduleService;
import com.intermanage.backend.staff.service.StaffTimeService;
import com.intermanage.backend.staff.service.StaffTimeSheetService;

import jakarta.validation.Valid;

/**
 * Karty czasu pracy. Uprawnienie mówi, kto w ogóle decyduje; o którym dziale, sprawdza serwis (kierownik tylko swój).
 * Ustawienia (dane pracodawcy, czytniki, karty NFC) zmienia właściciel albo kadry ({@code STAFF:EDIT}).
 */
@RestController
@RequestMapping("/api/stores/{storeId}/staff/time")
public class StaffTimeController {

    private static final String VIEW =
            "hasAuthority('STAFF_TIME:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT =
            "hasAuthority('STAFF_TIME:EDIT') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String CONFIGURE = "hasAuthority('STAFF_TIME:VIEW') and hasAuthority('STAFF:EDIT')"
            + " and @storeAccess.canAccess(authentication, #storeId)";

    private final StaffTimeService time;
    private final StaffScheduleService schedules;
    private final StaffTimeSheetService sheets;

    public StaffTimeController(StaffTimeService time, StaffScheduleService schedules, StaffTimeSheetService sheets) {
        this.time = time;
        this.schedules = schedules;
        this.sheets = sheets;
    }

    /** Karta ewidencji czasu pracy za miesiąc: cały dział, a z {@code employeeId} jedna osoba. */
    @GetMapping("/sheet/pdf")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> sheetPdf(@PathVariable UUID storeId, @RequestParam UUID departmentId,
            @RequestParam YearMonth month, @RequestParam(required = false) UUID employeeId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        StaffTimeSheetService.File file = sheets.pdf(storeId, departmentId, month, employeeId, principal);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(file.content());
    }

    /** Plik importu ewidencji czasu pracy do Symfonii R2Płatnik; pracownika wskazuje numer karty RCP. */
    @GetMapping("/sheet/rcp")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> sheetRcp(@PathVariable UUID storeId, @RequestParam YearMonth month,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        StaffTimeSheetService.File file = sheets.rcp(storeId, month, principal);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType("text/plain;charset=windows-1250"))
                .body(file.content());
    }

    /** Godziny sklepu za miesiąc z numerami kart RCP, do programu kadrowo-płacowego. */
    @GetMapping("/sheet/xlsx")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> sheetXlsx(@PathVariable UUID storeId, @RequestParam YearMonth month,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        StaffTimeSheetService.File file = sheets.xlsx(storeId, month, principal);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file.content());
    }

    /** Saldo okresu pracownika (jak w grafiku) do okna decyzji o nadgodzinach. */
    @GetMapping("/balance")
    @PreAuthorize(VIEW)
    public TimeBalanceResponse balance(@PathVariable UUID storeId, @RequestParam UUID employeeId,
            @RequestParam LocalDate date) {
        return schedules.balance(storeId, employeeId, date);
    }

    @GetMapping("/departments")
    @PreAuthorize(VIEW)
    public List<ScheduleDepartment> departments(@PathVariable UUID storeId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return time.departments(storeId, principal);
    }

    /** Licznik w menu: dni do reakcji kierownika z ostatnich 31 dni. */
    @GetMapping("/attention")
    @PreAuthorize(VIEW)
    public StaffTimeService.Attention attention(@PathVariable UUID storeId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return time.attention(storeId, principal);
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public TimeCardResponse view(@PathVariable UUID storeId, @RequestParam UUID departmentId,
            @RequestParam LocalDate from, @RequestParam LocalDate to,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return time.view(storeId, departmentId, from, to, principal);
    }

    @PostMapping("/decisions")
    @PreAuthorize(EDIT)
    public Map<String, UUID> decide(@PathVariable UUID storeId, @RequestParam UUID departmentId,
            @Valid @RequestBody TimeRequests.Decision request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return Map.of("id", time.decide(storeId, departmentId, request, principal));
    }

    @DeleteMapping("/decisions/{decisionId}")
    @PreAuthorize(EDIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeDecision(@PathVariable UUID storeId, @PathVariable UUID decisionId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        time.removeDecision(storeId, decisionId, principal);
    }

    @PostMapping("/decisions/{decisionId}/restore")
    @PreAuthorize(EDIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restoreDecision(@PathVariable UUID storeId, @PathVariable UUID decisionId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        time.restoreDecision(storeId, decisionId, principal);
    }

    @GetMapping("/decisions/{decisionId}/pdf")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> decisionPdf(@PathVariable UUID storeId, @PathVariable UUID decisionId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        StaffTimeService.PdfFile file = time.decisionPdf(storeId, decisionId, principal);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(file.content());
    }

    /** Podpisane polecenie albo wniosek (skan, zdjęcie); wymiana daje poprzedniemu plikowi znacznik. */
    @PutMapping("/decisions/{decisionId}/file")
    @PreAuthorize(EDIT)
    public Map<String, UUID> attachFile(@PathVariable UUID storeId, @PathVariable UUID decisionId,
            @RequestParam("file") MultipartFile file, @AuthenticationPrincipal AppUserPrincipal principal) {
        return Map.of("id", time.attachFile(storeId, decisionId, file, principal));
    }

    @GetMapping("/decisions/{decisionId}/file")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> signedFile(@PathVariable UUID storeId, @PathVariable UUID decisionId) {
        StaffTimeService.StoredFile file = time.signedFile(storeId, decisionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    @DeleteMapping("/decisions/{decisionId}/file")
    @PreAuthorize(EDIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFile(@PathVariable UUID storeId, @PathVariable UUID decisionId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        time.removeFile(storeId, decisionId, principal);
    }

    @PostMapping("/decisions/{decisionId}/file/{fileId}/restore")
    @PreAuthorize(EDIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restoreFile(@PathVariable UUID storeId, @PathVariable UUID decisionId, @PathVariable UUID fileId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        time.restoreFile(storeId, decisionId, fileId, principal);
    }

    /** Symulator odbić (tylko właściciel, sprawdza serwis). */
    @PostMapping("/punches")
    @PreAuthorize(VIEW)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void simulate(@PathVariable UUID storeId, @Valid @RequestBody TimeRequests.SimulatedPunch request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        time.simulate(storeId, request, principal);
    }

    @DeleteMapping("/punches/{punchId}")
    @PreAuthorize(VIEW)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removePunch(@PathVariable UUID storeId, @PathVariable UUID punchId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        time.removePunch(storeId, punchId, principal);
    }

    @GetMapping("/settings")
    @PreAuthorize(CONFIGURE)
    public TimeSettingsResponse settings(@PathVariable UUID storeId) {
        return time.settings(storeId);
    }

    @PutMapping("/settings/employer")
    @PreAuthorize(CONFIGURE)
    public TimeSettingsResponse saveEmployer(@PathVariable UUID storeId,
            @Valid @RequestBody TimeRequests.Employer request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return time.saveEmployer(storeId, request, principal.getId());
    }

    @PostMapping("/settings/readers")
    @PreAuthorize(CONFIGURE)
    public TimeSettingsResponse addReader(@PathVariable UUID storeId, @Valid @RequestBody TimeRequests.Reader request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return time.addReader(storeId, request, principal.getId());
    }

    @DeleteMapping("/settings/readers/{readerId}")
    @PreAuthorize(CONFIGURE)
    public TimeSettingsResponse removeReader(@PathVariable UUID storeId, @PathVariable UUID readerId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return time.removeReader(storeId, readerId, principal.getId());
    }

    @PostMapping("/settings/readers/{readerId}/restore")
    @PreAuthorize(CONFIGURE)
    public TimeSettingsResponse restoreReader(@PathVariable UUID storeId, @PathVariable UUID readerId) {
        return time.restoreReader(storeId, readerId);
    }

    @PutMapping("/settings/cards/{employeeId}")
    @PreAuthorize(CONFIGURE)
    public TimeSettingsResponse saveCard(@PathVariable UUID storeId, @PathVariable UUID employeeId,
            @Valid @RequestBody TimeRequests.Card request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return time.saveCard(storeId, employeeId, request, principal.getId());
    }

    @DeleteMapping("/settings/cards/{employeeId}")
    @PreAuthorize(CONFIGURE)
    public TimeSettingsResponse removeCard(@PathVariable UUID storeId, @PathVariable UUID employeeId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return time.removeCard(storeId, employeeId, principal.getId());
    }
}
