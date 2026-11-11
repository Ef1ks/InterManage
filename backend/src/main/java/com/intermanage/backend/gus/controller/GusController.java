package com.intermanage.backend.gus.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.common.component.Nip;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.gus.component.GusClient;
import com.intermanage.backend.gus.dto.GusCompany;
import com.intermanage.backend.gus.service.GusService;

/**
 * „Pobierz z GUS” przy formularzach z NIP-em: dodawanie i edycja dostawcy, sklep w Ustawieniach, dane pracodawcy
 * w Czasie pracy. Niczego nie zapisuje, formularz sam wpisuje dane w pola.
 */
@RestController
@RequestMapping("/api/gus")
public class GusController {

    private final GusService gus;

    public GusController(GusService gus) {
        this.gus = gus;
    }

    /** Dane publiczne z rejestru, niczego nie zapisuje: słownik dla każdego zalogowanego. */
    @GetMapping("/companies")
    @PreAuthorize("isAuthenticated()")
    public GusCompany company(@RequestParam String nip) {
        String digits = Nip.parse(nip, "nip");
        try {
            return gus.find(digits)
                    .orElseThrow(() -> ApiException.notFound("GUS nie ma firmy o NIP-ie " + digits));
        } catch (GusClient.Unavailable exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }
}
