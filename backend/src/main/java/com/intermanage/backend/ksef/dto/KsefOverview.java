package com.intermanage.backend.ksef.dto;

import java.time.Instant;
import java.util.List;

/**
 * Nagłówek listy faktur KSeF: czy sklep ma połączenie, kiedy ostatnio pobrano faktury i z jakim wynikiem, trwające
 * pobieranie ({@code syncRunning}, od kiedy i ile nowych faktur już zapisano) oraz IDWew sklepu do filtra.
 */
public record KsefOverview(
        boolean configured,
        String nip,
        Instant syncedAt,
        Boolean syncOk,
        String syncMessage,
        boolean syncRunning,
        Instant syncStartedAt,
        int syncNewInvoices,
        long invoiceCount,
        List<KsefInternalIdResponse> internalIds) {
}
