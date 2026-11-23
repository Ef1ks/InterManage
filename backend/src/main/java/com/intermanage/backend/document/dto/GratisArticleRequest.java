package com.intermanage.backend.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Artykuł z kartoteki sklepu do dodania na listę gratisów (ITM8 jak w kartotece). */
public record GratisArticleRequest(@NotBlank @Size(max = 13) String itm8) {
}
