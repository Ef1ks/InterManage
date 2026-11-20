package com.intermanage.backend.store.dto;

/**
 * Folder sklepu na stronie „Ścieżki folderów”: {@code custom} = sklep ma własną ścieżkę (tylko dla siebie), inaczej
 * domyślna aplikacji. {@code folder} to skrót ścieżki (ten sam folder = ten sam skrót). {@code path} i
 * {@code defaultPath} tylko dla właściciela, pozostali ich nie dostają.
 */
public record FolderPathResponse(String key, boolean custom, String folder, String path, String defaultPath) {
}
