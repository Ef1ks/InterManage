package com.intermanage.backend.bakery.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.bakery.dto.BakeryComparison;
import com.intermanage.backend.bakery.dto.BakeryInvoice;
import com.intermanage.backend.bakery.entity.BakeryCheck;
import com.intermanage.backend.bakery.entity.BakeryCheckDocument;
import com.intermanage.backend.bakery.entity.BakeryWz;
import com.intermanage.backend.bakery.entity.BakeryWzLine;
import com.intermanage.backend.bakery.repository.BakeryCheckRepository;
import com.intermanage.backend.bakery.repository.BakeryWzRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.dto.CatalogProduct;
import com.intermanage.backend.dataentry.dto.DataEntryLine;
import com.intermanage.backend.dataentry.service.DataEntryLinkService;
import com.intermanage.backend.dataentry.service.DataEntryService;
import com.intermanage.backend.document.dto.CorrectedDelivery;
import com.intermanage.backend.document.service.DeliveryKsefCorrectionService;
import com.intermanage.backend.ksef.component.KsefInvoiceReader;

/**
 * Podsumowanie weryfikacji: suma WZ kontra faktura z korektami kontra przyjęcie po korektach, po artykule. Pozycje faktur
 * łączą się z artykułami tak samo jak we wprowadzaniu danych, razem z przypięciami kodów dostawcy, więc przypięcie
 * zrobione raz działa w obu miejscach. Bez wspólnej transakcji: faktura, której teraz nie da się pobrać z KSeF, trafia
 * do {@code unavailable} zamiast przerywać podsumowanie. Korekty mają ilości ze znakiem, a wiersz "przed korektą"
 * się odejmuje, więc suma pozycji to zawsze zmiana wprowadzona przez dokument.
 */
@Service
public class BakeryComparisonService {

    private static final int PRICE_SCALE = 4;
    private static final int VALUE_SCALE = 2;

    private final BakeryCheckRepository checks;
    private final BakeryWzRepository wzs;
    private final BakeryCheckService service;
    private final DataEntryService dataEntry;
    private final DeliveryKsefCorrectionService ksefCorrections;
    private final TransactionTemplate transaction;

    public BakeryComparisonService(BakeryCheckRepository checks, BakeryWzRepository wzs, BakeryCheckService service,
            DataEntryService dataEntry, DeliveryKsefCorrectionService ksefCorrections,
            PlatformTransactionManager transactionManager) {
        this.checks = checks;
        this.wzs = wzs;
        this.service = service;
        this.dataEntry = dataEntry;
        this.ksefCorrections = ksefCorrections;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setReadOnly(true);
    }

    /** Sumy jednego artykułu ze wszystkich dokumentów. */
    private static final class Article {
        String itm8;
        String ean;
        String name;
        String linkCode;
        String invoiceUnit;
        BigDecimal wz = BigDecimal.ZERO;
        BigDecimal invoiceQuantity = BigDecimal.ZERO;
        BigDecimal correctionQuantity = BigDecimal.ZERO;
        BigDecimal invoiceValue = BigDecimal.ZERO;
        BigDecimal correctionValue = BigDecimal.ZERO;
        BigDecimal bonQuantity = BigDecimal.ZERO;
        BigDecimal bonValue = BigDecimal.ZERO;
        /** Cena z ostatniej pozycji faktury, gdy po korekcie ilość wyszła 0 i nie ma z czego jej policzyć. */
        BigDecimal lastPrice;

        void describe(String itm8, String ean, String name) {
            this.itm8 = this.itm8 != null ? this.itm8 : itm8;
            this.ean = this.ean != null ? this.ean : ean;
            this.name = this.name != null ? this.name : name;
        }
    }

    private record Snapshot(Map<String, Article> articles, List<BakeryInvoice> invoices, boolean hasBon, String sellerNip,
            String sellerName, long pendingCorrections) {
    }

    public BakeryComparison compare(UUID storeId, UUID id) {
        Snapshot snapshot = Objects.requireNonNull(transaction.execute(status -> snapshot(storeId, id)));
        Map<String, Article> articles = snapshot.articles();
        List<BakeryComparison.Unavailable> unavailable = new ArrayList<>();
        for (BakeryInvoice invoice : snapshot.invoices()) {
            try {
                dataEntry.invoiceLines(storeId, invoice.id()).forEach(line -> addInvoiceLine(articles, line, invoice));
            } catch (ApiException e) {
                unavailable.add(new BakeryComparison.Unavailable(invoice.invoiceNumber(), e.getMessage()));
            }
        }
        boolean hasInvoice = snapshot.invoices().size() > unavailable.size();
        List<BakeryComparison.Row> rows = articles.entrySet().stream()
                .map(entry -> row(entry.getKey(), entry.getValue(), hasInvoice, snapshot.hasBon()))
                .sorted(Comparator.comparing((BakeryComparison.Row row) -> row.status() == BakeryComparison.Status.OK)
                        .thenComparing(row -> row.differenceValue() != null ? row.differenceValue().abs()
                                : BigDecimal.ZERO, Comparator.reverseOrder())
                        .thenComparing(BakeryComparison.Row::name, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        return new BakeryComparison(rows, totals(rows, articles), unavailable, snapshot.pendingCorrections(),
                snapshot.hasBon(), snapshot.sellerNip(), snapshot.sellerName());
    }

    private Snapshot snapshot(UUID storeId, UUID id) {
        BakeryCheck check = checks.findByIdAndStoreId(id, storeId).filter(found -> !found.isDeleted())
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono weryfikacji"));
        Map<String, Article> articles = new LinkedHashMap<>();
        for (BakeryWz wz : wzs.findWithLines(List.of(id))) {
            for (BakeryWzLine line : wz.getLines()) {
                if (line.getQuantity() != null) {
                    Article article = article(articles, key(line.getProduct().getItm8()));
                    article.describe(line.getProduct().getItm8(), line.getProduct().getEan(),
                            line.getProduct().getName());
                    article.wz = article.wz.add(line.getQuantity());
                }
            }
        }
        List<BakeryCheckDocument> bons = checks.findDocuments(List.of(id));
        for (BakeryCheckDocument bon : bons) {
            for (CorrectedDelivery.CorrectedLine line : service.corrected(storeId, bon.getDocument()).lines()) {
                Article article = article(articles, key(line.itm8()));
                article.describe(line.itm8(), line.ean(), line.name());
                article.bonQuantity = article.bonQuantity.add(zero(line.quantity()));
                article.bonValue = article.bonValue.add(zero(line.purchaseValue()));
            }
        }
        String sellerNip = DataEntryLinkService.digits(check.getSupplier().getNip());
        return new Snapshot(articles, service.get(storeId, id).invoices(), !bons.isEmpty(), sellerNip,
                check.getSupplier().getName(), ksefCorrections.pendingCorrections(storeId, sellerNip));
    }

    private static void addInvoiceLine(Map<String, Article> articles, DataEntryService.InvoiceLine line,
            BakeryInvoice invoice) {
        KsefInvoiceReader.Line source = line.source();
        if (source.deposit()) {
            return;
        }
        DataEntryLine row = line.row();
        CatalogProduct product = row.product();
        boolean known = product != null && product.itm8() != null;
        String code = row.ean() != null ? row.ean() : row.fallbackCode();
        String key = known ? key(product.itm8()) : "kod:" + (code != null ? code : row.invoiceName());
        Article article = article(articles, key);
        if (known) {
            article.describe(product.itm8(), product.catalogEan(), product.name());
        } else {
            article.describe(null, row.ean(), row.invoiceName());
            article.linkCode = code;
            article.invoiceUnit = row.invoiceUnit();
        }
        BigDecimal quantity = zero(source.quantity());
        BigDecimal signedQuantity = source.beforeCorrection() ? quantity.negate() : quantity;
        if (invoice.correction()) {
            article.correctionQuantity = article.correctionQuantity.add(signedQuantity);
            article.correctionValue = article.correctionValue.add(source.signedNet());
        } else {
            article.invoiceQuantity = article.invoiceQuantity.add(signedQuantity);
            article.invoiceValue = article.invoiceValue.add(source.signedNet());
        }
        if (row.unitNet() != null) {
            article.lastPrice = row.unitNet();
        }
    }

    private static BakeryComparison.Row row(String key, Article article, boolean hasInvoice, boolean hasBon) {
        BigDecimal finalQuantity = article.invoiceQuantity.add(article.correctionQuantity);
        BigDecimal finalValue = article.invoiceValue.add(article.correctionValue);
        BigDecimal reference = hasInvoice ? finalQuantity : article.bonQuantity;
        BigDecimal difference = hasInvoice || hasBon ? article.wz.subtract(reference) : null;
        BigDecimal price = price(article, finalQuantity, finalValue);
        BigDecimal differenceValue = difference != null && price != null
                ? difference.multiply(price).setScale(VALUE_SCALE, RoundingMode.HALF_UP) : null;
        return new BakeryComparison.Row(key, article.itm8, article.ean, article.name, article.wz,
                hasInvoice ? article.invoiceQuantity : null, hasInvoice ? article.correctionQuantity : null,
                hasInvoice ? finalQuantity : null, price, hasInvoice ? finalValue : null,
                hasBon ? article.bonQuantity : null, hasBon ? article.bonValue : null, difference, differenceValue,
                status(article, finalQuantity, hasInvoice, hasBon), article.linkCode, article.invoiceUnit);
    }

    private static BakeryComparison.Status status(Article article, BigDecimal finalQuantity, boolean hasInvoice,
            boolean hasBon) {
        if (article.itm8 == null) {
            return BakeryComparison.Status.NO_ARTICLE;
        }
        if (hasInvoice && article.wz.compareTo(finalQuantity) != 0) {
            return BakeryComparison.Status.WZ;
        }
        BigDecimal reference = hasInvoice ? finalQuantity : article.wz;
        return hasBon && article.bonQuantity.compareTo(reference) != 0
                ? BakeryComparison.Status.BON : BakeryComparison.Status.OK;
    }

    /** Cena netto: z faktury z korektami, przy ilości 0 z ostatniej pozycji, bez faktury z przyjęcia. */
    private static BigDecimal price(Article article, BigDecimal finalQuantity, BigDecimal finalValue) {
        if (finalQuantity.signum() != 0) {
            return finalValue.divide(finalQuantity, PRICE_SCALE, RoundingMode.HALF_UP);
        }
        if (article.lastPrice != null) {
            return article.lastPrice;
        }
        return article.bonQuantity.signum() != 0
                ? article.bonValue.divide(article.bonQuantity, PRICE_SCALE, RoundingMode.HALF_UP) : null;
    }

    private static BakeryComparison.Totals totals(List<BakeryComparison.Row> rows, Map<String, Article> articles) {
        BigDecimal wzValue = BigDecimal.ZERO;
        BigDecimal difference = BigDecimal.ZERO;
        for (BakeryComparison.Row row : rows) {
            if (row.price() != null) {
                wzValue = wzValue.add(row.wzQuantity().multiply(row.price()));
            }
            difference = difference.add(zero(row.differenceValue()));
        }
        BigDecimal invoice = articles.values().stream().map(article -> article.invoiceValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal correction = articles.values().stream().map(article -> article.correctionValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal bon = articles.values().stream().map(article -> article.bonValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int differences = (int) rows.stream().filter(row -> row.status() != BakeryComparison.Status.OK).count();
        return new BakeryComparison.Totals(wzValue.setScale(VALUE_SCALE, RoundingMode.HALF_UP), invoice, correction,
                invoice.add(correction), bon, difference, differences);
    }

    private static Article article(Map<String, Article> articles, String key) {
        return articles.computeIfAbsent(key, ignored -> new Article());
    }

    /** ITM8 bez zer wiodących: kartoteka ma 13 znaków, a raporty bywają krótsze. */
    private static String key(String itm8) {
        return itm8.replaceFirst("^0+", "");
    }

    private static BigDecimal zero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
