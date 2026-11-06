package com.intermanage.backend.deliprice.dto;

import java.util.List;
import java.util.UUID;

/** Automatyczne przypisanie: nowe przypisania ({@code ids}, do cofnięcia) i ile wyrobów zostało bez artykułu. */
public record AutoLinkResponse(List<UUID> ids, int left) {
}
