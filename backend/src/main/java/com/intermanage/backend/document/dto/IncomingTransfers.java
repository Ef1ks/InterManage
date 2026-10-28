package com.intermanage.backend.document.dto;

import java.util.List;

/** Ruchy międzymagazynowe od innych sklepów: czekające na wprowadzenie i ostatnio wprowadzone. */
public record IncomingTransfers(
        List<IncomingTransfer> pending,
        List<IncomingTransfer> entered) {
}
