package com.intermanage.backend.download.dto;

/** Lista zmian wersji: w każdym polu jeden wpis w linii (puste pole = brak wpisów tego rodzaju). */
public record DownloadChangesRequest(String added, String changed, String fixed) {
}
