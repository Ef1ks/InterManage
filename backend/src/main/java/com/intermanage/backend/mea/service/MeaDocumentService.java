package com.intermanage.backend.mea.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.common.component.XlsReader;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.common.exception.InvalidWorkbookException;
import com.intermanage.backend.dataentry.dto.CatalogProduct;
import com.intermanage.backend.dataentry.service.DataEntryService;
import com.intermanage.backend.mea.component.MeaFormParser;
import com.intermanage.backend.mea.component.MurphysoftClient;
import com.intermanage.backend.mea.dto.MeaDocumentResponse;
import com.intermanage.backend.mea.dto.MeaItem;
import com.intermanage.backend.mea.entity.MeaDocument;
import com.intermanage.backend.mea.entity.MeaDocumentLine;
import com.intermanage.backend.mea.repository.MeaDocumentRepository;
import com.intermanage.backend.mea.repository.MeaOrderRepository;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.repository.ProductRepository;

/**
 * Formularze MEA w bazie, wspólne dla sklepów: z list MEA powstają wpisy ({@link #register}), a formularz pobiera się
 * raz z portalu ({@link #fetch}, w tle albo przy pierwszym otwarciu dokumentu). Pobieranie jest poza transakcją
 * (zapytanie do portalu trwa), zapis każdego formularza we własnej.
 */
@Service
public class MeaDocumentService {

    private static final Logger log = LoggerFactory.getLogger(MeaDocumentService.class);
    /** Po tylu nieudanych próbach formularza nie pobieramy w tle (otwarcie dokumentu dalej próbuje). */
    public static final int MAX_ATTEMPTS = 5;
    /** Przerwa między próbami tego samego formularza. */
    public static final Duration RETRY_AFTER = Duration.ofHours(6);
    /** Tyle kodów {@link DataEntryService#lookup} przyjmuje w jednym wywołaniu. */
    private static final int LOOKUP_BATCH = 500;
    /** ITM8 w kartotece ma 13 cyfr z zerami wiodącymi, w formularzu MEA 8. */
    private static final int ITM8_LENGTH = 13;

    private final MeaDocumentRepository documents;
    private final MeaService mea;
    private final MurphysoftClient client;
    private final MeaFormParser parser;
    private final XlsReader xlsReader;
    private final ProductRepository products;
    private final DataEntryService dataEntry;
    private final MeaAthosService athos;
    private final MeaOrderRepository orders;
    private final TransactionTemplate transaction;

    public MeaDocumentService(MeaDocumentRepository documents, MeaService mea, MurphysoftClient client,
            MeaFormParser parser, XlsReader xlsReader, ProductRepository products, DataEntryService dataEntry,
            MeaAthosService athos, MeaOrderRepository orders, PlatformTransactionManager transactionManager) {
        this.documents = documents;
        this.mea = mea;
        this.client = client;
        this.parser = parser;
        this.xlsReader = xlsReader;
        this.products = products;
        this.dataEntry = dataEntry;
        this.athos = athos;
        this.orders = orders;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Wpisy dla MEA z listy, których jeszcze nie ma (z plikiem .xls); niepobranym odświeża dane z listy. */
    public void register(List<MeaItem> items) {
        Map<String, MeaItem> byNumber = new HashMap<>();
        for (MeaItem item : items) {
            if (item.number().matches("\\d{4,6}") && xlsPath(item) != null) {
                byNumber.putIfAbsent(item.number(), item);
            }
        }
        if (byNumber.isEmpty()) {
            return;
        }
        transaction.executeWithoutResult(status -> {
            Map<String, MeaDocument> existing = documents.findByNumberIn(byNumber.keySet()).stream()
                    .collect(Collectors.toMap(MeaDocument::getNumber, Function.identity()));
            byNumber.forEach((number, item) -> {
                MeaDocument document = existing.get(number);
                if (document == null) {
                    document = documents.save(new MeaDocument(number, xlsPath(item)));
                }
                document.describe(item.name(), item.promoFrom(), item.promoTo(), item.reservationFrom(),
                        item.reservationTo(), xlsPath(item));
            });
        });
    }

    /** Numery formularzy do pobrania w tle (najnowsze promocje najpierw), najwyżej {@code limit}. */
    public List<String> pending(int limit) {
        return transaction.execute(status -> documents
                .findPending(MAX_ATTEMPTS, Instant.now().minus(RETRY_AFTER), Limit.of(limit)).stream()
                .map(MeaDocument::getNumber).toList());
    }

    /**
     * Pobiera formularz z portalu sesją sklepu i zapisuje pozycje. Nieudana próba zostaje w {@code last_error};
     * portal niedostępny ({@link MurphysoftClient.Unavailable}) leci dalej, bo kolejne pobrania też by padły.
     */
    public void fetch(UUID storeId, String number) {
        String path = transaction.execute(status -> documents.findByNumber(number)
                .map(MeaDocument::getFilePath).orElse(defaultPath(number)));
        MeaService.Session session = mea.session(storeId);
        byte[] file = client.meaFile(session.shopId(), session.token(), path);
        MeaFormParser.Form form;
        try {
            form = parser.parse(xlsReader.readFirstSheet(file));
            if (!number.equals(form.number())) {
                throw new MeaFormParser.InvalidForm("Portal oddał formularz MEA " + form.number() + " zamiast " + number);
            }
        } catch (InvalidWorkbookException | MeaFormParser.InvalidForm exception) {
            // Portal oddaje JSON z błędem zamiast pliku (np. formularz jeszcze się wgrywa albo go usunięto).
            String error = exception instanceof InvalidWorkbookException ? errorText(file) : exception.getMessage();
            transaction.executeWithoutResult(status -> document(number, path).failed(error));
            throw new MurphysoftClient.Unavailable("Nie udało się wczytać formularza MEA " + number + ": " + error);
        }
        transaction.executeWithoutResult(status -> {
            MeaDocument document = document(number, path);
            if (document.isFetched()) {
                return;
            }
            document.fill(form.title(), form.promoFrom(), form.promoTo(), form.reservationFrom(),
                    form.reservationTo(), form.dispatches(), form.lines().stream().map(MeaDocumentService::line)
                            .toList());
        });
        log.info("MEA {}: pobrano formularz, {} pozycji", number, form.lines().size());
    }

    /**
     * Dokument MEA z pozycjami połączonymi z kartoteką sklepu; formularz z bazy, a gdy go jeszcze nie ma, pobiera go
     * od razu. Artykuł szukamy po ITM8 z formularza (MEA, potem równoległy), a bez trafienia po EAN-ie (bieżący,
     * multikody, dawne pary), bo MEA ma własny ITM8 promocyjny (61665965), a sklep sprzedaje artykuł pod stałym
     * (61462142).
     */
    public MeaDocumentResponse document(UUID storeId, String number) {
        MeaDocument document = load(storeId, number);
        List<MeaDocumentLine> lines = document.getLines();
        Map<String, CatalogProduct> catalog = catalog(storeId, lines);
        return new MeaDocumentResponse(document.getNumber(), document.getTitle(), document.getPromoFrom(),
                document.getPromoTo(), document.getReservationFrom(), document.getReservationTo(),
                document.getDispatches(),
                lines.stream().map(line -> new MeaDocumentResponse.Line(line.getLp(), line.getMeaItm8(),
                        line.getParallelItm8(), line.getEan(), line.getName(), line.getBrand(),
                        line.getPacksPerPallet(), line.getUnitsPerPack(), line.getVatPercent(),
                        line.getPurchasePrice(), line.getParallelPurchasePrice(), line.getShelfPrice(),
                        line.getParallelShelfPrice(), line.getMargin(), line.getParallelMargin(),
                        line.getGuaranteedDays(), line.getNote(), catalog.get(line.getMeaItm8()))).toList(),
                athos.sends(storeId, number).stream().map(send -> new MeaDocumentResponse.AthosSend(
                        send.getSentAt(), send.getPositions(), send.getColis())).toList(),
                orders.findByStoreIdAndNumberAndSavedAtIsNotNullOrderBySavedAtDesc(storeId, number).stream()
                        .map(order -> new MeaDocumentResponse.Order(order.getSavedAt(), order.getFileName(),
                                order.getPositions(), order.getColis(),
                                order.getCreatedBy() == null ? null : order.getCreatedBy().getDisplayName()))
                        .toList(),
                orders.findQueued(storeId, number).stream().findFirst()
                        .map(order -> new MeaDocumentResponse.Queued(order.getId(), order.getQueuedAt(),
                                order.getPositions(), order.getColis(),
                                order.getCreatedBy() == null ? null : order.getCreatedBy().getDisplayName()))
                        .orElse(null));
    }

    /** Formularz MEA z pozycjami z bazy; bez niego pobiera go od razu z portalu sesją sklepu. */
    public MeaDocument load(UUID storeId, String number) {
        if (number == null || !number.matches("\\d{4,6}")) {
            throw ApiException.badRequest("Nieprawidłowy numer MEA");
        }
        MeaDocument document = documents.findWithLinesByNumber(number).orElse(null);
        if (document == null || !document.isFetched()) {
            fetch(storeId, number);
            document = documents.findWithLinesByNumber(number)
                    .orElseThrow(() -> ApiException.notFound("Nie znaleziono MEA " + number));
        }
        return document;
    }

    private MeaDocument document(String number, String path) {
        return documents.findByNumber(number).orElseGet(() -> documents.save(new MeaDocument(number, path)));
    }

    private static MeaDocumentLine line(MeaFormParser.Line line) {
        return new MeaDocumentLine(line.lp(), line.meaItm8(), line.parallelItm8(), line.ean(), line.name(),
                line.brand(), line.packsPerPallet(), line.unitsPerPack(), line.vatPercent(), line.purchasePrice(),
                line.parallelPurchasePrice(), line.shelfPrice(), line.parallelShelfPrice(), line.margin(),
                line.parallelMargin(), line.guaranteedDays(), line.note());
    }

    /** Artykuł z kartoteki dla każdej pozycji (klucz: ITM8 MEA). */
    private Map<String, CatalogProduct> catalog(UUID storeId, List<MeaDocumentLine> lines) {
        Set<String> itm8s = new HashSet<>();
        for (MeaDocumentLine line : lines) {
            itm8s.add(catalogItm8(line.getMeaItm8()));
            if (line.getParallelItm8() != null) {
                itm8s.add(catalogItm8(line.getParallelItm8()));
            }
        }
        Map<String, Product> byItm8 = new HashMap<>();
        products.findByStoreIdAndItm8In(storeId, itm8s).forEach(product -> byItm8.putIfAbsent(product.getItm8(), product));
        Map<String, String> keys = new HashMap<>();
        for (MeaDocumentLine line : lines) {
            Product product = byItm8.get(catalogItm8(line.getMeaItm8()));
            if (product == null && line.getParallelItm8() != null) {
                product = byItm8.get(catalogItm8(line.getParallelItm8()));
            }
            String key = eanKey(product != null && product.getEan() != null ? product.getEan() : line.getEan());
            if (key != null) {
                keys.put(line.getMeaItm8(), key);
            }
        }
        List<String> eans = keys.values().stream().distinct().toList();
        Map<String, CatalogProduct> found = new HashMap<>();
        for (int from = 0; from < eans.size(); from += LOOKUP_BATCH) {
            dataEntry.lookup(storeId, eans.subList(from, Math.min(from + LOOKUP_BATCH, eans.size())))
                    .forEach(product -> found.putIfAbsent(product.ean(), product));
        }
        Map<String, CatalogProduct> result = new HashMap<>();
        keys.forEach((itm8, key) -> {
            CatalogProduct product = found.get(key);
            if (product != null) {
                result.put(itm8, product);
            }
        });
        return result;
    }

    /** Ścieżka formularza .xls z listy plików MEA. */
    private static String xlsPath(MeaItem item) {
        return item.files().stream().filter(file -> file.toLowerCase().endsWith(".xls")).findFirst().orElse(null);
    }

    /** Ścieżka formularza, gdy MEA nie było na liście: {@code data/mea/xls/<3 pierwsze cyfry>/<nr>.xls}. */
    private static String defaultPath(String number) {
        return "../../data/mea/xls/" + number.substring(0, 3) + "/" + number + ".xls";
    }

    private static String errorText(byte[] file) {
        String text = new String(file, 0, Math.min(file.length, 300), java.nio.charset.StandardCharsets.UTF_8).strip();
        return text.isEmpty() ? "pusty plik" : "portal nie oddał pliku (" + text + ")";
    }

    private static String catalogItm8(String itm8) {
        return itm8.length() >= ITM8_LENGTH ? itm8 : "0".repeat(ITM8_LENGTH - itm8.length()) + itm8;
    }

    /** EAN jako klucz kartoteki: same cyfry bez zer wiodących. */
    private static String eanKey(String ean) {
        if (ean == null) {
            return null;
        }
        String digits = ean.replaceAll("\\D", "").replaceFirst("^0+", "");
        return digits.isEmpty() ? null : digits;
    }
}
