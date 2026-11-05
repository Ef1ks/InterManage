package com.intermanage.backend.document.dto;

import java.util.UUID;

import com.intermanage.backend.store.dto.StoreSummary;

/** Ruch międzymagazynowy innego sklepu, który ten dokument potwierdza; {@code id} to id wpisu do wprowadzenia. */
public record IncomingTransferRef(UUID id, String number, StoreSummary fromStore) {
}
