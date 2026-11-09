package com.intermanage.backend.product.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.product.dto.ArticleCodesResponse;
import com.intermanage.backend.product.dto.ArticleCodesResponse.Code;
import com.intermanage.backend.product.dto.ArticleCodesResponse.Multicode;
import com.intermanage.backend.product.dto.ArticleCodesResponse.OtherArticle;
import com.intermanage.backend.product.repository.ArticleCodeRepository;
import com.intermanage.backend.product.repository.ArticleCodeRepository.CodeRow;
import com.intermanage.backend.product.repository.ArticleCodeRepository.OtherRow;

/**
 * Kody artykułu do karty „Kody EAN i ITM8”: EAN-y z kartoteki, par z raportów i multikodów połączone po EAN-ie bez zer
 * wiodących (te same kody bywają zapisane z zerami i bez), a przy każdym inne artykuły z tym kodem.
 */
@Service
public class ArticleCodesService {

    private final ArticleCodeRepository codes;

    public ArticleCodesService(ArticleCodeRepository codes) {
        this.codes = codes;
    }

    @Transactional(readOnly = true)
    public ArticleCodesResponse codes(UUID storeId, String itm8) {
        String key = itm8.strip();
        List<CodeRow> rows = codes.findCodes(storeId, key);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Artykuł " + key + " nie ma kodów w kartotece ani w multikodach");
        }

        String mainEan = null;
        String name = null;
        Map<String, Builder> byKey = new LinkedHashMap<>();
        Set<String> spellings = new HashSet<>();
        for (CodeRow row : rows) {
            if (row.getEan() == null || row.getEan().isBlank()) {
                continue;
            }
            spellings.add(row.getEan());
            Builder code = byKey.computeIfAbsent(eanKey(row.getEan()), ignored -> new Builder(row.getEan()));
            switch (row.getSource()) {
                case "MAIN" -> {
                    mainEan = row.getEan();
                    name = row.getName();
                    code.main = true;
                    code.ean = row.getEan();
                }
                case "CATALOG" -> {
                    code.catalogFirstSeen = earlier(code.catalogFirstSeen, row.getFirstSeen());
                    code.catalogLastSeen = later(code.catalogLastSeen, row.getLastSeen());
                }
                default -> {
                    if (name == null) {
                        name = row.getName();
                    }
                    Multicode multicode = new Multicode(Boolean.TRUE.equals(row.getPrio()),
                            Boolean.TRUE.equals(row.getCurrent()), row.getSaleUnit(), row.getSalePack(),
                            row.getPriceFactor(), row.getSalePrice(), row.getRemovalDate(), row.getFirstSeen(),
                            row.getLastSeen());
                    // Ten sam kod zapisany dwa razy (z zerami i bez): wygrywa obowiązujący.
                    if (code.multicode == null || (!code.multicode.current() && multicode.current())) {
                        code.multicode = multicode;
                    }
                }
            }
        }

        Map<String, List<OtherArticle>> others = new LinkedHashMap<>();
        if (!byKey.isEmpty()) {
            for (OtherRow row : codes.findOtherArticles(storeId, key, byKey.keySet(), spellings)) {
                others.computeIfAbsent(row.getEanKey(), ignored -> new ArrayList<>())
                        .add(new OtherArticle(row.getItm8(), row.getName(), row.getSource()));
            }
        }

        List<Code> result = byKey.entrySet().stream()
                .map(entry -> entry.getValue().build(others.getOrDefault(entry.getKey(), List.of())))
                .sorted(Comparator.comparingInt(ArticleCodesService::rank).thenComparing(Code::ean))
                .toList();
        return new ArticleCodesResponse(key, name, mainEan, result);
    }

    /** Kolejność: główny, kod główny z multikodów, obowiązujące multikody, pary z raportów, dawne multikody. */
    private static int rank(Code code) {
        if (code.main()) {
            return 0;
        }
        Multicode multicode = code.multicode();
        if (multicode != null && multicode.current()) {
            return multicode.prio() ? 1 : 2;
        }
        return code.catalogLastSeen() != null ? 3 : 4;
    }

    private static String eanKey(String ean) {
        String stripped = ean.strip().replaceFirst("^0+", "");
        return stripped.isEmpty() ? "0" : stripped;
    }

    private static LocalDateTime earlier(LocalDateTime a, LocalDateTime b) {
        return a == null ? b : b == null || a.isBefore(b) ? a : b;
    }

    private static LocalDateTime later(LocalDateTime a, LocalDateTime b) {
        return a == null ? b : b == null || a.isAfter(b) ? a : b;
    }

    private static final class Builder {
        private String ean;
        private boolean main;
        private LocalDateTime catalogFirstSeen;
        private LocalDateTime catalogLastSeen;
        private Multicode multicode;

        private Builder(String ean) {
            this.ean = ean;
        }

        private Code build(List<OtherArticle> otherArticles) {
            return new Code(ean, main, catalogFirstSeen, catalogLastSeen, multicode, otherArticles);
        }
    }
}
