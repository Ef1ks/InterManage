package com.intermanage.backend.sales.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** ITM8 artykułów do podpięcia albo odpięcia od dostawcy do kontroli strat. */
public record LossControlArticlesRequest(@NotEmpty @Size(max = 5000) List<String> itm8s) {
}
