package com.intermanage.backend.agent.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.agent.dto.AgentMacroApi;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.component.InvoiceCloseMacro;
import com.intermanage.backend.dataentry.service.DataEntryInvoiceClosingService;

/**
 * Zamknięcie faktur z kolejki wprowadzania danych na komputerze sklepu. Potwierdzenie („Zamknąć dostawy?”) jest na
 * stronie przed zleceniem, a wynik (zamknięte i pominięte dostawy) wraca na stronę jak przy innych zdalnych makrach.
 * Faktury stają się wygenerowane przy zleceniu, tak jak po skopiowaniu skryptu do schowka.
 */
@Service
public class InvoiceCloseMacroService {

    private final AgentMacroService macros;
    private final DataEntryInvoiceClosingService closings;

    public InvoiceCloseMacroService(AgentMacroService macros, DataEntryInvoiceClosingService closings) {
        this.macros = macros;
        this.closings = closings;
    }

    @Transactional
    public AgentMacroApi.Job run(UUID storeId, AgentMacroApi.InvoiceCloseRequest request, UUID userId) {
        if (!request.confirmed()) {
            throw ApiException.badRequest("Potwierdź zamknięcie faktur");
        }
        List<InvoiceCloseMacro.Document> documents = closings.forMacro(storeId, request.ids());
        if (documents.size() != request.ids().stream().distinct().count()) {
            throw ApiException.conflict("Części faktur nie ma już na liście do zamknięcia. Odśwież okno.");
        }
        List<String> wrong = documents.stream().filter(document -> !InvoiceCloseMacro.fits(document))
                .map(InvoiceCloseMacro.Document::number).toList();
        if (!wrong.isEmpty()) {
            throw ApiException.badRequest("Tych numerów faktur makro nie wpisze: " + String.join(", ", wrong));
        }
        AgentMacroApi.Job job = macros.invoiceClose(storeId, InvoiceCloseMacro.build(documents, request.print()),
                documents.size(), request.agentId(), userId);
        closings.markGenerated(storeId, request.ids(), userId);
        return job;
    }
}
