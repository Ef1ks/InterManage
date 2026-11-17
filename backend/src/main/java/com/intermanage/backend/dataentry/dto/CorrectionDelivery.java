package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Przyjęcie PZ do wyboru przy fakturze korygującej. {@code matched}: przyjęcie faktury, którą ta korekta koryguje
 * (po numerze faktury, przypiętej fakturze albo numerze WZ z XML faktury), reszta to podpowiedzi i wyniki wyszukiwania.
 * {@code commonArticles}: ile artykułów korekty jest w przyjęciu (przy {@code matched} z kilkoma przyjęciami i przy
 * podpowiedziach, inaczej 0); front sam wybiera przyjęcie z największą liczbą. {@code supplierCode}: dostawca bonu,
 * od niego zależy typ protokołu korekty w Mercalys (baza 538 i 88110 typ 9, reszta, także refaktura 16538, typ 8).
 * {@code suggested}: bez przyjęcia faktury korygowanej przyjęcie dostawcy z okolic jej daty z artykułami korekty;
 * {@code quantityMatches} z {@code quantityArticles}: przy ilu artykułach z wierszem „przed korektą” ilość na bonie
 * jest taka jak na fakturze (tak pracownik szukał bonu w statystykach artykułu).
 */
public record CorrectionDelivery(UUID id, String number, LocalDateTime documentDate, String supplierCode,
        String supplierName, String supplierDocumentNumber, BigDecimal purchaseValue, boolean matched,
        int commonArticles, boolean suggested, int quantityMatches, int quantityArticles) {
}
