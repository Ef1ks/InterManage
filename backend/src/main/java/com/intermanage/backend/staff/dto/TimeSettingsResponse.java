package com.intermanage.backend.staff.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ustawienia kart czasu pracy w sklepie: dane pracodawcy do wydruków (wspólne dla firmy; bez zapisu podpowiedź ze
 * sklepu, {@code saved} = false), czytniki NFC i karty pracowników.
 */
public record TimeSettingsResponse(Employer employer, List<Reader> readers, List<Card> cards) {

    /** {@code saved}: zapisane dla firmy; bez tego podpowiedź, z {@code fromGus} z GUS po NIP-ie sklepu. */
    public record Employer(String name, String address, String nip, String regon, String representative,
            boolean saved, boolean fromGus) {
    }

    /** {@code token}: klucz czytnika, tylko w odpowiedzi na jego dodanie. */
    public record Reader(UUID id, String name, Instant createdAt, String token) {
    }

    /** {@code rcpNumber}: numer karty RCP do wpisania w Symfonii; {@code cardUid}: karta NFC do czytnika. */
    public record Card(UUID employeeId, String name, String departmentName, String cardUid, Integer rcpNumber) {
    }
}
