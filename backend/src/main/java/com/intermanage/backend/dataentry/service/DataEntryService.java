package com.intermanage.backend.dataentry.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.XlsxWriter;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.component.CorrectionRows;
import com.intermanage.backend.dataentry.component.InvoiceXml;
import com.intermanage.backend.dataentry.component.Measure;
import com.intermanage.backend.dataentry.component.ProductCodeSources;
import com.intermanage.backend.dataentry.component.ProductFile;
import com.intermanage.backend.dataentry.dto.CatalogListItem;
import com.intermanage.backend.dataentry.dto.CatalogListPage;
import com.intermanage.backend.dataentry.dto.CatalogListRequest;
import com.intermanage.backend.dataentry.dto.CatalogProduct;
import com.intermanage.backend.dataentry.dto.DataEntryInvoice;
import com.intermanage.backend.dataentry.dto.DataEntryLine;
import com.intermanage.backend.dataentry.dto.ExportRequest;
import com.intermanage.backend.dataentry.dto.NomenclatureItem;
import com.intermanage.backend.dataentry.entity.DataEntrySuggestion;
import com.intermanage.backend.dataentry.entity.PriceRoundingRule;
import com.intermanage.backend.dataentry.repository.DataEntryCatalogRepository;
import com.intermanage.backend.dataentry.repository.DataEntryProductLinkRepository;
import com.intermanage.backend.dataentry.repository.DataEntrySuggestionRepository;
import com.intermanage.backend.ksef.component.KsefInvoiceReader;
import com.intermanage.backend.ksef.dto.KsefInvoiceSummary;
import com.intermanage.backend.ksef.service.KsefInvoiceService;
import com.intermanage.backend.nomenclature.entity.Nomenclature;
import com.intermanage.backend.nomenclature.repository.NomenclatureRepository;
import com.intermanage.backend.nomenclature.service.NomenclatureMarginService;
import com.intermanage.backend.product.entity.ProductDraft;
import com.intermanage.backend.product.repository.ProductDraftRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Wprowadzanie danych: pozycje faktury z KSeF dopasowane po EAN-ie do kartoteki sklepu, wyszukiwanie artykułów po
 * EAN-ie dla wierszy wpisanych ręcznie, nomenklatura sklepu, zapis tabeli do pliku XML faktury dla programu sklepu i
 * eksport do Excela (wersja testowa).
 */
@Service
public class DataEntryService {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** Ilość × cena może różnić się od wartości pozycji o zaokrąglenie; więcej to rabat albo cena sprzed korekty. */
    private static final BigDecimal VALUE_TOLERANCE = new BigDecimal("0.01");
    private static final Pattern RATE = Pattern.compile("^(\\d+(?:[.,]\\d+)?)\\s*%");
    private static final Pattern XML_EAN = Pattern.compile("\\d{8,14}");
    private static final Pattern FULL_EAN = Pattern.compile("\\d{8}|\\d{12,14}");
    static final int CATALOG_PAGE_SIZE = 100;
    /** {@code eanSource} pozycji wczytanej z przypięcia kodu dostawcy do artykułu. */
    static final String LINK_SOURCE = "LINK";
    /** {@code eanSource} pozycji wczytanej z pliku XML faktury. */
    static final String XML_SOURCE = "XML";
    static final Set<String> CATALOG_SORTS =
            Set.of("itm8", "ean", "name", "brand", "nomenclature", "purchase", "sale", "stock", "rotation");
    /** Filtr dostawcy listy towarów: artykuły z przyjęć PZ od tego dostawcy z tylu ostatnich miesięcy. */
    static final int CATALOG_SUPPLIER_MONTHS = 6;

    private final KsefInvoiceService ksefInvoices;
    private final KsefInvoiceReader reader;
    private final DataEntryCatalogRepository catalog;
    private final NomenclatureRepository nomenclatures;
    private final DataEntrySuggestionRepository suggestions;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final XlsxWriter xlsxWriter;
    private final NomenclatureMarginService margins;
    private final PriceRoundingService rounding;
    private final SupplierRepository suppliers;
    private final DataEntryProductLinkRepository productLinks;
    private final ProductDraftRepository productDrafts;

    public DataEntryService(KsefInvoiceService ksefInvoices, KsefInvoiceReader reader,
            DataEntryCatalogRepository catalog, NomenclatureRepository nomenclatures,
            DataEntrySuggestionRepository suggestions, StoreRepository stores, AppUserRepository users,
            XlsxWriter xlsxWriter, NomenclatureMarginService margins, PriceRoundingService rounding,
            SupplierRepository suppliers, DataEntryProductLinkRepository productLinks,
            ProductDraftRepository productDrafts) {
        this.ksefInvoices = ksefInvoices;
        this.reader = reader;
        this.catalog = catalog;
        this.nomenclatures = nomenclatures;
        this.suggestions = suggestions;
        this.stores = stores;
        this.users = users;
        this.xlsxWriter = xlsxWriter;
        this.margins = margins;
        this.rounding = rounding;
        this.suppliers = suppliers;
        this.productLinks = productLinks;
        this.productDrafts = productDrafts;
    }

    public record ExportFile(String fileName, byte[] content) {
    }

    /**
     * Pozycje faktury (XML z bazy, za pierwszym razem z KSeF w granicach limitu pobrań) z artykułami z kartoteki.
     * EAN pozycji z wybranego źródła ({@link ProductCodeSources}: AUTO, GTIN, INDEX, EXTRA:klucz, NAME); odpowiedź
     * podaje też, ile pozycji ma kod w każdym źródle i ile z nich jest w kartotece. Faktura musi być widoczna w sklepie
     * (IDWew), inaczej 404.
     */
    @Transactional
    public DataEntryInvoice invoice(UUID storeId, UUID invoiceId, String source) {
        return load(storeId, invoiceId, source, false).invoice();
    }

    /** Pozycja faktury z artykułem z kartoteki razem z pozycją z XML (kwota netto, wiersz "przed korektą", kaucja). */
    public record InvoiceLine(DataEntryLine row, KsefInvoiceReader.Line source) {
    }

    /**
     * Wszystkie pozycje faktury z artykułami, źródło EAN-u automatyczne. W przeciwieństwie do {@link #invoice} z wierszami
     * "przed korektą", bo weryfikacja dokumentów liczy, o ile korekta zmieniła ilości, a nie jaki jest stan po niej.
     */
    @Transactional
    public List<InvoiceLine> invoiceLines(UUID storeId, UUID invoiceId) {
        LoadedInvoice loaded = load(storeId, invoiceId, ProductCodeSources.AUTO, true);
        List<InvoiceLine> result = new ArrayList<>();
        for (int i = 0; i < loaded.lines().size(); i++) {
            result.add(new InvoiceLine(loaded.invoice().lines().get(i), loaded.lines().get(i)));
        }
        return result;
    }

    private record LoadedInvoice(DataEntryInvoice invoice, List<KsefInvoiceReader.Line> lines) {
    }

    private LoadedInvoice load(UUID storeId, UUID invoiceId, String source, boolean withBeforeCorrection) {
        KsefInvoiceSummary summary = ksefInvoices.summary(storeId, invoiceId);
        byte[] xml = ksefInvoices.xml(storeId, invoiceId).content();
        KsefInvoiceReader.Invoice invoice;
        List<KsefInvoiceReader.LineCodes> codes;
        DataEntryInvoice.Correction correction;
        try {
            invoice = reader.read(xml);
            codes = reader.lineCodes(xml);
            correction = invoice.correction() ? correction(invoice, codes, reader.correctedInvoices(xml)) : null;
        } catch (KsefInvoiceReader.InvalidInvoiceException e) {
            throw ApiException.badRequest("Nie udało się odczytać faktury z KSeF: " + e.getMessage());
        }
        List<String> keys = ProductCodeSources.keys(codes);
        String chosen = source == null || source.isBlank() ? ProductCodeSources.AUTO : source.strip();
        if (!ProductCodeSources.AUTO.equals(chosen) && !keys.contains(chosen)) {
            throw ApiException.badRequest("Nieznane źródło kodu towaru: " + chosen);
        }

        // Pozycje i ich kody idą w tej samej kolejności FaWiersz; wiersze "przed korektą" pomijamy.
        Map<Integer, Integer> beforeRows = CorrectionRows.pairs(codes);
        Set<Integer> addedRows = CorrectionRows.added(codes, beforeRows);
        List<KsefInvoiceReader.Line> lines = new ArrayList<>();
        List<KsefInvoiceReader.Line> linesBefore = new ArrayList<>();
        List<KsefInvoiceReader.LineCodes> lineCodes = new ArrayList<>();
        // Wiersz "przed" bez pary to artykuł, który odszedł z dostawy: w tabeli jest z ilością 0.
        Set<Integer> removedLines = new HashSet<>();
        Set<Integer> addedLines = new HashSet<>();
        for (int i = 0; i < invoice.lines().size(); i++) {
            KsefInvoiceReader.Line current = invoice.lines().get(i);
            boolean removed = !withBeforeCorrection && current.beforeCorrection() && !beforeRows.containsValue(i);
            if (withBeforeCorrection || !current.beforeCorrection() || removed) {
                if (removed) {
                    removedLines.add(lines.size());
                }
                if (addedRows.contains(i)) {
                    addedLines.add(lines.size());
                }
                lines.add(current);
                linesBefore.add(removed ? current
                        : beforeRows.containsKey(i) ? invoice.lines().get(beforeRows.get(i)) : null);
                lineCodes.add(codes.get(i));
            }
        }
        List<String> candidates = lineCodes.stream()
                .flatMap(line -> keys.stream().flatMap(key -> ProductCodeSources.candidates(line, key).stream()))
                .map(DataEntryService::key).filter(Objects::nonNull).distinct().toList();
        Map<String, CatalogProduct> known = lookupByKey(storeId, candidates);
        Predicate<String> inCatalog = code -> known.containsKey(key(code));

        // Bez EAN-u kolumna dostaje inny kod z faktury (najpierw z wybranego źródła) albo nazwę, żeby pozycję dało się
        // rozpoznać. Pozycja spoza kartoteki z kodem przypiętym wcześniej u tego dostawcy wczytuje się od razu jako
        // przypięty artykuł; przypięcie szukamy po kodzie z kolumny, a potem po każdym innym kodzie pozycji (indeks
        // piekarni przypięty raz działa też przy wczytaniu automatycznym).
        List<ProductCodeSources.Pick> picks = new ArrayList<>();
        List<ProductCodeSources.Pick> fallbacks = new ArrayList<>();
        List<List<String>> linkCodes = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            ProductCodeSources.Pick pick = ProductCodeSources.pick(lineCodes.get(i), chosen, keys, inCatalog);
            String ean = ean(pick.code());
            ProductCodeSources.Pick fallback = ean == null
                    ? ProductCodeSources.fallback(lineCodes.get(i), keys, chosen) : null;
            picks.add(pick);
            fallbacks.add(fallback);
            String code = ean != null ? ean : fallback != null ? fallback.code() : null;
            List<String> codesOfLine = new ArrayList<>();
            if (code != null && (ean == null || !known.containsKey(key(ean)) || incompleteEan(ean))) {
                codesOfLine.add(DataEntryLinkService.code(code));
                ProductCodeSources.rawCodes(lineCodes.get(i), keys).stream().map(DataEntryLinkService::code)
                        .filter(other -> !codesOfLine.contains(other)).forEach(codesOfLine::add);
            }
            linkCodes.add(codesOfLine);
        }
        List<String> unmatched = linkCodes.stream().flatMap(List::stream).distinct().toList();
        String sellerNip = DataEntryLinkService.digits(summary.sellerNip());
        Map<String, DataEntryProductLinkRepository.ActiveLink> linked = unmatched.isEmpty() || sellerNip.isEmpty()
                ? Map.of()
                : productLinks.findActive(storeId, sellerNip, unmatched).stream()
                        .collect(Collectors.toMap(DataEntryProductLinkRepository.ActiveLink::getCode,
                                Function.identity(), (a, b) -> a));
        Map<String, CatalogProduct> linkedProducts = lookupByKey(storeId, linked.values().stream()
                .map(link -> key(link.getEan())).filter(Objects::nonNull).distinct().toList());

        List<DataEntryLine> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            KsefInvoiceReader.Line line = lines.get(i);
            KsefInvoiceReader.Line before = linesBefore.get(i);
            ProductCodeSources.Pick pick = picks.get(i);
            ProductCodeSources.Pick fallback = fallbacks.get(i);
            String ean = ean(pick.code());
            String eanSource = pick.source();
            BigDecimal vat = vatPercent(line.rate());
            CatalogProduct product = ean != null ? known.get(key(ean)) : null;
            DataEntryProductLinkRepository.ActiveLink link = null;
            if (product == null || incompleteEan(ean)) {
                link = linkCodes.get(i).stream().map(linked::get).filter(Objects::nonNull).findFirst().orElse(null);
                CatalogProduct linkedProduct = link != null ? linkedProducts.get(key(link.getEan())) : null;
                if (linkedProduct != null) {
                    product = linkedProduct;
                    ean = linkedProduct.catalogEan();
                    eanSource = LINK_SOURCE;
                    fallback = null;
                } else {
                    link = null;
                }
            }
            Measure measure = product != null ? Measure.of(product.unit(), product.netContent())
                    : Measure.fromInvoice(line.name(), line.unit());
            if (measure == null) {
                measure = Measure.piece();
            }
            rows.add(new DataEntryLine(line.number(), ean, eanSource,
                    fallback != null ? fallback.code() : null, fallback != null ? fallback.source() : null,
                    removedLines.contains(i) ? BigDecimal.ZERO : line.quantity(), unitNet(line, vat),
                    line.name() != null ? line.name().strip() : null, line.unit(), vat, product, measure.unit(),
                    measure.netContent(), link != null ? link.getId() : null, link != null ? link.getCode() : null,
                    lineCodes.get(i).index(), lineCodes.get(i).cn(),
                    before != null ? before.quantity() : addedLines.contains(i) ? BigDecimal.ZERO : null,
                    before != null ? unitNet(before, vatPercent(before.rate()))
                            : addedLines.contains(i) ? unitNet(line, vat) : null));
        }
        List<KsefInvoiceReader.Line> counted = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!removedLines.contains(i)) {
                counted.add(lines.get(i));
            }
        }
        return new LoadedInvoice(new DataEntryInvoice(summary.id(), summary.invoiceNumber(), summary.sellerName(),
                summary.sellerNip(), summary.issueDate(), chosen, ProductCodeSources.sources(lineCodes, inCatalog).stream()
                        .map(item -> new DataEntryInvoice.CodeSource(item.key(), item.label(), item.filled(),
                                item.matched()))
                        .toList(),
                rows, total(invoice, counted, KsefInvoiceReader.TaxRow::net, KsefInvoiceReader.Line::net),
                total(invoice, counted, KsefInvoiceReader.TaxRow::vat, KsefInvoiceReader.Line::vat), correction), lines);
    }

    private static DataEntryInvoice.Correction correction(KsefInvoiceReader.Invoice invoice,
            List<KsefInvoiceReader.LineCodes> codes, List<KsefInvoiceReader.CorrectedInvoice> corrected) {
        String reason = invoice.correctionDetails().stream().filter(entry -> "Przyczyna korekty".equals(entry.label()))
                .map(KsefInvoiceReader.Entry::value).findFirst().orElse(null);
        return new DataEntryInvoice.Correction(reason,
                codes.stream().anyMatch(KsefInvoiceReader.LineCodes::beforeCorrection),
                corrected.stream().map(item -> new DataEntryInvoice.CorrectedInvoice(item.number(), item.issueDate(),
                        item.ksefNumber())).toList());
    }

    /**
     * Suma z faktury: z podsumowania stawek (P_13_x, P_14_x), a gdy go nie ma, z pozycji bez wierszy "przed korektą".
     * Brak kwoty w którymkolwiek wierszu daje {@code null}, bo suma byłaby niepełna.
     */
    private static BigDecimal total(KsefInvoiceReader.Invoice invoice, List<KsefInvoiceReader.Line> lines,
            Function<KsefInvoiceReader.TaxRow, BigDecimal> fromTax, Function<KsefInvoiceReader.Line, BigDecimal> fromLine) {
        List<BigDecimal> amounts = invoice.taxes().isEmpty()
                ? lines.stream().map(fromLine).toList()
                : invoice.taxes().stream().map(fromTax).toList();
        if (amounts.isEmpty() || amounts.contains(null)) {
            return null;
        }
        return amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Pierwszy i ostatni kod artykułu z etykiety wagi (prefiks 24), który sklep może nadać nowemu artykułowi. */
    static final int FREE_SCALE_CODE_FROM = 240001;
    static final int FREE_SCALE_CODE_TO = 242999;

    /**
     * Wolne kody dla nowych artykułów ważonych: 240001-242999 bez kodów zajętych w sklepie, jako pełny EAN z zerami
     * w miejscu ceny ({@code 2400010000000}), jak towar na wagę w kartotece.
     */
    @Transactional(readOnly = true)
    public List<String> freeScaleCodes(UUID storeId) {
        Set<String> used = Set.copyOf(catalog.findUsedScaleCodes(storeId));
        List<String> free = new ArrayList<>();
        for (int code = FREE_SCALE_CODE_FROM; code <= FREE_SCALE_CODE_TO; code++) {
            String prefix = Integer.toString(code);
            if (!used.contains(prefix)) {
                free.add(prefix + "0000000");
            }
        }
        return free;
    }

    /** Artykuły z kartoteki dla EAN-ów (bez zer wiodących); nieznane EAN-y pomija. */
    @Transactional(readOnly = true)
    public List<CatalogProduct> lookup(UUID storeId, Collection<String> eans) {
        return lookup(storeId, eans, false);
    }

    /**
     * Jak {@link #lookup(UUID, Collection)}, a z {@code byItm8} (kody wpisane w kolumnę EAN) kod, który nie jest
     * żadnym EAN-em artykułu, szuka też po ITM8. Tylko dla kodów od człowieka: indeks dostawcy z faktury trafiałby
     * w przypadkowe ITM8.
     */
    @Transactional(readOnly = true)
    public List<CatalogProduct> lookup(UUID storeId, Collection<String> eans, boolean byItm8) {
        List<String> keys = eans.stream().map(DataEntryService::ean).map(DataEntryService::key)
                .filter(Objects::nonNull).distinct().limit(500).toList();
        return List.copyOf(lookupByKey(storeId, keys, byItm8).values());
    }

    /**
     * Lista towarów z kartoteki z filtrem nad każdą kolumną; pole dzieli się na słowa i każde musi pasować, w dowolnej
     * kolejności ({@code kab ex 19} znajdzie „KABANOSY EXCLUSIVE 190G”). Słowo to fragment, {@code *} w nim zastępuje
     * dowolny ciąg znaków, {@code ?} jeden znak; w ITM8 i EAN słowo z gwiazdką jest całym kodem jak w Excelu
     * ({@code 5923532*} = kody od 5923532). Pole „Szukaj” ({@code query}): każde słowo w nazwie albo marce (marki
     * producentów i rodzaje mięsa są w kartotece tylko w marce: „fil kur” znajdzie „FILET SWIEZY KL A” marki KURCZAK),
     * EAN-ie (też z multikodów, gdy słowo ma cyfrę) albo ITM8. Nomenklatura
     * z samych cyfr (spacje i kropki pomijane) to początek kodu podrodziny, inny tekst to nazwa któregoś poziomu.
     * Dostawca: kod albo fragment nazwy z linii przyjęć PZ z ostatnich {@link #CATALOG_SUPPLIER_MONTHS} miesięcy, bo
     * dostawca z kartoteki bywa błędny; przy każdym artykule strony jego ostatnia dostawa (drugie zapytanie, tylko dla
     * strony). Sortowanie po dowolnej kolumnie ({@link #CATALOG_SORTS}, domyślnie po nazwie), strony po
     * {@link #CATALOG_PAGE_SIZE}.
     */
    @Transactional(readOnly = true)
    public CatalogListPage catalogList(UUID storeId, CatalogListRequest request) {
        int offset = Math.max(request.page() != null ? request.page() : 0, 0) * CATALOG_PAGE_SIZE;
        String nomenclature = request.nomenclature() != null ? request.nomenclature().strip() : "";
        String code = nomenclature.replaceAll("[\\s.]", "");
        boolean byCode = code.matches("\\d{1,6}");
        String[] supplier = likePatterns(request.supplier(), true);
        String sort = request.sort() != null && CATALOG_SORTS.contains(request.sort()) ? request.sort() : "name";
        String direction = "desc".equals(request.direction()) ? "desc" : "asc";
        String[] queryCodes = likePatterns(request.query(), false);
        List<DataEntryCatalogRepository.CatalogListItem> found = catalog.search(storeId,
                likePatterns(request.query(), true), queryCodes,
                Arrays.stream(queryCodes).map(pattern -> pattern.matches(".*\\d.*") ? pattern : null)
                        .toArray(String[]::new),
                likePatterns(request.itm8(), false), likePatterns(request.ean(), false),
                likePatterns(request.name(), true), likePatterns(request.brand(), true),
                supplier, supplier.length > 0 ? request.supplier().strip() : null,
                LocalDate.now(WARSAW).minusMonths(CATALOG_SUPPLIER_MONTHS), byCode ? code : null,
                byCode ? new String[0] : likePatterns(nomenclature, true), request.purchaseFrom(), request.purchaseTo(),
                request.saleFrom(), request.saleTo(), sort, direction, CATALOG_PAGE_SIZE + 1, offset);
        List<DataEntryCatalogRepository.CatalogListItem> page = found.stream().limit(CATALOG_PAGE_SIZE).toList();
        List<String> itm8s = page.stream().map(DataEntryCatalogRepository.CatalogListItem::getItm8)
                .filter(Objects::nonNull).toList();
        Map<String, CatalogListItem.LastDelivery> deliveries = itm8s.isEmpty() ? Map.of()
                : catalog.findLastDeliveries(storeId, itm8s)
                        .stream().collect(Collectors.toMap(DataEntryCatalogRepository.LastDelivery::getItm8,
                                row -> new CatalogListItem.LastDelivery(row.getSupplierCode(), row.getSupplierName(),
                                        row.getDocumentDate().toLocalDate())));
        List<CatalogListItem> items = page.stream()
                .map(row -> new CatalogListItem(row.getItm8(), row.getEan(), row.getName(), row.getBrand(),
                        row.getSupplier(), row.getSubfamilyCode(), row.getSubfamilyName(), row.getPurchasePrice(),
                        row.getSalePrice(), row.getStock(), row.getRotation(),
                        row.getItm8() == null ? null : deliveries.get(row.getItm8()), row.getDraft()))
                .toList();
        return new CatalogListPage(items, found.size() > CATALOG_PAGE_SIZE);
    }

    /**
     * Wzorce LIKE z tekstu z filtra, po jednym na słowo (każde musi pasować). {@code text}: wielkie litery bez polskich
     * znaków, jak porównuje baza, a słowo to fragment ({@code %…%}). Kod ({@code text = false}): słowo z {@code *} albo
     * {@code ?} jest całym kodem ({@code 5923532*} = początek kodu), bez nich fragmentem. {@code *} = dowolny ciąg,
     * {@code ?} = jeden znak, znaki specjalne LIKE dosłownie. Pusty tekst = pusta tablica.
     */
    public static String[] likePatterns(String text, boolean plain) {
        if (text == null || text.isBlank()) {
            return new String[0];
        }
        String value = text.strip();
        if (plain) {
            value = java.text.Normalizer.normalize(value.toUpperCase(Locale.ROOT).replace('Ł', 'L'),
                    java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        }
        return Arrays.stream(value.split("\\s+")).map(word -> likePattern(word, plain)).toArray(String[]::new);
    }

    private static String likePattern(String word, boolean plain) {
        boolean whole = !plain && (word.indexOf('*') >= 0 || word.indexOf('?') >= 0);
        StringBuilder pattern = new StringBuilder(whole ? "" : "%");
        for (char c : word.toCharArray()) {
            switch (c) {
                case '*' -> pattern.append('%');
                case '?' -> pattern.append('_');
                case '%', '_', '\\' -> pattern.append('\\').append(c);
                default -> pattern.append(c);
            }
        }
        return pattern.append(whole ? "" : "%").toString();
    }

    /** Cała nomenklatura sklepu (rejony, rodziny, podrodziny) w kolejności kodów, z obowiązującą marżą wymaganą. */
    @Transactional(readOnly = true)
    public List<NomenclatureItem> nomenclature(UUID storeId) {
        Map<String, BigDecimal> required = margins.effectiveByCode(storeId);
        return nomenclatures.findAllByStore(storeId).stream()
                .map(item -> new NomenclatureItem(item.getCode(), item.getName(), required.get(item.getCode())))
                .toList();
    }

    /**
     * Tabela w pliku Excel: kolumny jak na ekranie, wartość netto, marża i cena sugerowana przeliczone, nomenklatura
     * z nazwami.
     * Wiersze z propozycji AI zapisują wyeksportowane wartości przy propozycji (co ludzie poprawiają).
     */
    @Transactional
    public ExportFile export(UUID storeId, ExportRequest request, UUID userId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        Map<String, String> names = nomenclatures.findAllByStore(storeId).stream()
                .collect(Collectors.toMap(Nomenclature::getCode, Nomenclature::getName, (a, b) -> a));
        Map<String, BigDecimal> required = margins.effectiveByCode(storeId);
        List<PriceRoundingRule> roundingRules = rounding.active(storeId);

        List<XlsxWriter.Column> columns = List.of(
                new XlsxWriter.Column("EAN", 16), new XlsxWriter.Column("Ilość", 9),
                new XlsxWriter.Column("Cena netto", 11), new XlsxWriter.Column("Nazwa", 32),
                new XlsxWriter.Column("Marka", 16), new XlsxWriter.Column("Wartość netto", 13),
                new XlsxWriter.Column("VAT %", 7), new XlsxWriter.Column("Cena sprzedaży", 14),
                new XlsxWriter.Column("Marża %", 9), new XlsxWriter.Column("Cena sugerowana", 15),
                new XlsxWriter.Column("Rejon", 24),
                new XlsxWriter.Column("Rodzina", 28), new XlsxWriter.Column("Podrodzina", 30),
                new XlsxWriter.Column("Rodzaj sprzedaży", 10), new XlsxWriter.Column("Waga", 9),
                new XlsxWriter.Column("Nomenklatura", 16));
        List<XlsxWriter.Row> rows = new ArrayList<>();
        for (ExportRequest.Row row : request.rows()) {
            String subfamily = row.subfamilyCode() != null && row.subfamilyCode().length() == 6
                    ? row.subfamilyCode() : null;
            BigDecimal value = row.quantity() != null && row.unitNet() != null
                    ? row.quantity().multiply(row.unitNet()).setScale(2, RoundingMode.HALF_UP) : null;
            BigDecimal suggested = suggestedPrice(row, subfamily != null ? required.get(subfamily) : null,
                    roundingRules);
            rows.add(XlsxWriter.Row.of(
                    row.ean(), row.quantity(), row.unitNet() != null ? XlsxWriter.Cell.money(row.unitNet()) : null,
                    row.name(), row.brand(), value != null ? XlsxWriter.Cell.money(value) : null,
                    row.vatPercent(), row.salePrice() != null ? XlsxWriter.Cell.money(row.salePrice()) : null,
                    marginPercent(row), suggested != null ? XlsxWriter.Cell.money(suggested) : null,
                    level(names, subfamily, 2), level(names, subfamily, 4),
                    level(names, subfamily, 6), row.unit(), row.netContent(),
                    row.known() ? "Z kartoteki" : row.status() != null ? row.status().label()
                            : subfamily != null ? "Wybrana ręcznie" : "Brak"));
        }
        rows.add(XlsxWriter.Row.of());
        rows.add(XlsxWriter.Row.of(XlsxWriter.Cell.bold("Faktura"), request.invoiceNumber()));
        rows.add(XlsxWriter.Row.of(XlsxWriter.Cell.bold("Dostawca"), request.sellerName()));
        rows.add(XlsxWriter.Row.of(XlsxWriter.Cell.bold("Sklep"), store.getNumber() + " " + store.getName()));

        saveExported(storeId, request.rows(), userId);
        String day = LocalDate.now(WARSAW).format(DateTimeFormatter.BASIC_ISO_DATE);
        String invoice = request.invoiceNumber() != null && !request.invoiceNumber().isBlank()
                ? "_" + request.invoiceNumber().strip() : "";
        String fileName = ("wprowadzanie_" + store.getNumber() + invoice + "_" + day + ".xlsx")
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}\\s]+", "_");
        return new ExportFile(fileName, xlsxWriter.writeRows("Wprowadzanie", columns, rows));
    }

    /** Plik XML z tabeli z opisem nagłówka (do historii plików). */
    record XmlFile(String fileName, byte[] content, UUID invoiceId, String number, LocalDate date,
            String supplierName, String supplierNip, int lineCount, BigDecimal netValue) {
    }

    /**
     * Plik XML faktury dla programu sklepu ({@link InvoiceXml}), bez zapisu czegokolwiek. Nagłówek z faktury KSeF
     * wczytanej do tabeli (numer, data wystawienia, NIP sprzedawcy), a bez niej z dokumentu wczytanego z pliku XML
     * (bez daty: dzisiaj); dostawca to nazwa z listy dostawców sklepu po NIP-ie, bez niej nazwa sprzedawcy. Dostawca
     * wybrany w tabeli ({@code supplierId}) zastępuje sprzedawcę: nazwa, NIP i kod z listy, numer z tabeli. EAN tylko
     * z cyfr (8-14), inny kod (np. zastępczy z faktury) daje 13 zer. Nazwa pliku: numer sklepu, kod dostawcy (albo
     * NIP), nazwa dostawcy, numer faktury.
     */
    @Transactional(readOnly = true)
    XmlFile buildXml(UUID storeId, ExportRequest request) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        KsefInvoiceSummary invoice = request.invoiceId() != null
                ? ksefInvoices.summary(storeId, request.invoiceId()) : null;
        Supplier chosen = request.supplierId() == null ? null : suppliers.findById(request.supplierId())
                .filter(candidate -> candidate.getStore().getId().equals(storeId))
                .orElseThrow(() -> ApiException.badRequest("Nie znaleziono dostawcy w tym sklepie"));
        String number = invoice != null && chosen == null ? invoice.invoiceNumber()
                : blankToNull(request.invoiceNumber());
        String nip = chosen != null ? blankToNull(digits(chosen.getNip()))
                : invoice != null ? invoice.sellerNip() : blankToNull(digits(request.sellerNip()));
        Supplier supplier = chosen != null ? chosen : nip == null ? null : suppliers.findByStore(storeId).stream()
                .filter(candidate -> nip.equals(digits(candidate.getNip()))).findFirst().orElse(null);
        String supplierName = supplier != null ? supplier.getName()
                : invoice != null ? invoice.sellerName() : blankToNull(request.sellerName());
        LocalDate date = invoice != null && invoice.issueDate() != null ? invoice.issueDate()
                : request.issueDate() != null ? request.issueDate() : LocalDate.now(WARSAW);

        List<InvoiceXml.Line> lines = request.rows().stream()
                .map(row -> new InvoiceXml.Line(row.name(), xmlEan(row.ean()), row.unitNet(), row.vatPercent(),
                        row.quantity()))
                .toList();
        byte[] content = InvoiceXml.write(
                new InvoiceXml.Header(number, date, supplierName, store.getNumber(), nip), lines);

        String supplierPart = supplier != null && supplier.getCode() != null ? supplier.getCode() : nip;
        String supplierLabel = ProductFile.supplierPart(supplierName);
        String fileName = number != null
                ? String.join("_", store.getNumber(), supplierPart != null ? supplierPart : "", supplierLabel,
                        number.strip())
                : store.getNumber() + (supplierLabel.isEmpty() ? "" : "_" + supplierLabel) + "_wprowadzanie_"
                        + LocalDateTime.now(WARSAW).format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        fileName = fileName.replaceAll("[\\\\/:*?\"<>|()\\p{Cntrl}\\s]+", "_").replaceAll("_+", "_") + ".xml";
        return new XmlFile(fileName, content, invoice != null ? invoice.id() : null, number, date, supplierName, nip,
                lines.size(), InvoiceXml.netValue(lines));
    }

    /**
     * Plik XML faktury jako dokument do tabeli: pozycje dopasowane po EAN-ie do kartoteki, a bez EAN-u (albo spoza
     * kartoteki) z przypięciem kodu u dostawcy z NIP-u pliku; pozycja bez EAN-u ma nazwę jako kod zastępczy.
     * {@code invoice} to faktura z KSeF, z którą plik się wiąże (z niej dostawca i sumy do porównania); bez niej
     * dostawca i data z pliku, a suma netto z WART_NET.
     */
    @Transactional(readOnly = true)
    public DataEntryInvoice fromXml(UUID storeId, InvoiceXml.Document document, KsefInvoiceSummary invoice) {
        List<InvoiceXml.Line> lines = document.lines();
        Map<String, CatalogProduct> known = lookupByKey(storeId, lines.stream().map(line -> key(ean(line.ean())))
                .filter(Objects::nonNull).distinct().toList());
        String sellerNip = DataEntryLinkService.digits(invoice != null ? invoice.sellerNip()
                : document.header().supplierNip());
        List<String> linkCodes = lines.stream()
                .filter(line -> !known.containsKey(key(ean(line.ean()))))
                .map(line -> ean(line.ean()) != null ? ean(line.ean()) : line.name())
                .filter(Objects::nonNull).map(DataEntryLinkService::code).distinct().toList();
        Map<String, DataEntryProductLinkRepository.ActiveLink> linked = linkCodes.isEmpty() || sellerNip.isEmpty()
                ? Map.of()
                : productLinks.findActive(storeId, sellerNip, linkCodes).stream()
                        .collect(Collectors.toMap(DataEntryProductLinkRepository.ActiveLink::getCode,
                                Function.identity(), (a, b) -> a));
        Map<String, CatalogProduct> linkedProducts = lookupByKey(storeId, linked.values().stream()
                .map(link -> key(link.getEan())).filter(Objects::nonNull).distinct().toList());

        List<DataEntryLine> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            InvoiceXml.Line line = lines.get(i);
            String ean = ean(line.ean());
            String name = line.name() != null ? line.name().strip() : null;
            CatalogProduct product = ean != null ? known.get(key(ean)) : null;
            String eanSource = XML_SOURCE;
            DataEntryProductLinkRepository.ActiveLink link = null;
            if (product == null) {
                String code = ean != null ? ean : name;
                link = code != null ? linked.get(DataEntryLinkService.code(code)) : null;
                CatalogProduct linkedProduct = link != null ? linkedProducts.get(key(link.getEan())) : null;
                if (linkedProduct != null) {
                    product = linkedProduct;
                    ean = linkedProduct.catalogEan();
                    eanSource = LINK_SOURCE;
                } else {
                    link = null;
                }
            }
            Measure measure = product != null ? Measure.of(product.unit(), product.netContent())
                    : Measure.fromInvoice(name, null);
            if (measure == null) {
                measure = Measure.piece();
            }
            boolean fallback = ean == null && name != null;
            rows.add(new DataEntryLine(String.valueOf(i + 1), ean, eanSource, fallback ? name : null,
                    fallback ? "nazwa z pliku XML" : null, line.quantity(), line.unitNet(), name, null,
                    line.vatPercent(), product, measure.unit(), measure.netContent(),
                    link != null ? link.getId() : null, link != null ? link.getCode() : null, null, null, null, null));
        }
        InvoiceXml.Header header = document.header();
        return new DataEntryInvoice(invoice != null ? invoice.id() : null,
                invoice != null ? invoice.invoiceNumber() : header.documentNumber(),
                invoice != null ? invoice.sellerName() : header.supplierCode(),
                invoice != null ? invoice.sellerNip() : blankToNull(digits(header.supplierNip())),
                invoice != null && invoice.issueDate() != null ? invoice.issueDate() : header.date(), XML_SOURCE,
                List.of(), rows,
                invoice != null ? invoice.netAmount() : document.netValue(),
                invoice != null ? invoice.vatAmount() : null, null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String xmlEan(String ean) {
        String code = ean == null ? "" : ean.strip();
        return XML_EAN.matcher(code).matches() ? code : null;
    }

    /**
     * Kod z faktury o 9-11 cyfrach trafia w artykuł z kartoteki po zerach wiodących, ale jako EAN blokuje plik, więc
     * pozycję przypina się do artykułu, a przypięcie ma pierwszeństwo przed takim trafieniem.
     */
    private static boolean incompleteEan(String ean) {
        return ean != null && !FULL_EAN.matcher(ean).matches();
    }

    private static String digits(String value) {
        return value == null ? null : value.replaceAll("\\D", "");
    }

    /** Wiersze z propozycji AI zapisują wartości z pliku przy propozycji (do mierzenia trafności). */
    void saveExported(UUID storeId, List<ExportRequest.Row> rows, UUID userId) {
        Map<UUID, ExportRequest.Row> bySuggestion = rows.stream().filter(row -> row.suggestionId() != null)
                .collect(Collectors.toMap(ExportRequest.Row::suggestionId, Function.identity(), (a, b) -> b));
        if (bySuggestion.isEmpty()) {
            return;
        }
        AppUser user = userId != null ? users.getReferenceById(userId) : null;
        for (DataEntrySuggestion suggestion : suggestions.findInStore(storeId, bySuggestion.keySet())) {
            ExportRequest.Row row = bySuggestion.get(suggestion.getId());
            suggestion.exported(row.name(), row.brand(), row.subfamilyCode(), row.unit(), row.netContent(), user);
        }
    }

    /**
     * Marża % od sprzedaży netto: (sprzedaż netto − zakup netto sztuki) / sprzedaż netto. Zakup sztuki: cena z
     * faktury za jedną sztukę z kolumny Ilość bez względu na jednostkę (szt., op., pak, kg); waga nie zmienia marży.
     */
    static BigDecimal marginPercent(ExportRequest.Row row) {
        BigDecimal purchase = row.unitNet();
        if (purchase == null || row.salePrice() == null || row.salePrice().signum() <= 0 || row.vatPercent() == null) {
            return null;
        }
        BigDecimal saleNet = row.salePrice().multiply(HUNDRED)
                .divide(HUNDRED.add(row.vatPercent()), 6, RoundingMode.HALF_UP);
        return saleNet.subtract(purchase).multiply(HUNDRED).divide(saleNet, 1, RoundingMode.HALF_UP);
    }

    /**
     * Cena sprzedaży brutto, przy której artykuł osiąga marżę wymaganą: zakup sztuki / (1 − marża) × (1 + VAT),
     * w górę do grosza, potem do końcówki z przedziałów zaokrąglania. Artykuł z CENA_CAD ma ją jako cenę sugerowaną
     * (bez liczenia). Bez marży wymaganej albo bez ceny zakupu sztuki - pusta.
     */
    static BigDecimal suggestedPrice(ExportRequest.Row row, BigDecimal requiredMargin,
            List<PriceRoundingRule> roundingRules) {
        if (row.known() && row.cadencierPrice() != null && row.cadencierPrice().signum() > 0) {
            return row.cadencierPrice();
        }
        BigDecimal exact = suggestedPrice(row, requiredMargin);
        return exact == null ? null : PriceRoundingService.round(roundingRules, exact);
    }

    static BigDecimal suggestedPrice(ExportRequest.Row row, BigDecimal requiredMargin) {
        BigDecimal purchase = row.unitNet();
        if (requiredMargin == null || purchase == null || row.vatPercent() == null) {
            return null;
        }
        // Jedno dzielenie na końcu: pośrednie zaokrąglenie podbijałoby cenę o grosz (2,50 przy 30% i VAT 5% = 3,75).
        return purchase.multiply(HUNDRED).multiply(HUNDRED.add(row.vatPercent()))
                .divide(HUNDRED.subtract(requiredMargin).multiply(HUNDRED), 2, RoundingMode.CEILING);
    }

    private static String level(Map<String, String> names, String subfamily, int length) {
        if (subfamily == null) {
            return null;
        }
        String code = subfamily.substring(0, length);
        String name = names.get(code);
        return name != null ? code + " " + name : code;
    }

    private Map<String, CatalogProduct> lookupByKey(UUID storeId, List<String> keys) {
        return lookupByKey(storeId, keys, false);
    }

    private Map<String, CatalogProduct> lookupByKey(UUID storeId, List<String> keys, boolean byItm8) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        catalog.disableJit();
        Map<String, CatalogProduct> found = new HashMap<>();
        for (DataEntryCatalogRepository.CatalogProduct row : catalog.findByEans(storeId,
                keys.stream().distinct().toArray(String[]::new), byItm8, LocalDate.now(WARSAW))) {
            Measure measure = Measure.of(row.getUnit(), row.getNetContent());
            found.put(row.getKey(), new CatalogProduct(row.getKey(), row.getItm8(), row.getCatalogEan(), row.getName(),
                    row.getBrand(), row.getPurchasePrice(), row.getVatPercent(), row.getCatalogVat(), row.getSalePrice(), row.getCadencier(), row.getCadencierPrice(),
                    row.getPromoPrice(), row.getPromoTo(), measure != null ? measure.unit() : Measure.PIECE,
                    measure != null ? measure.netContent() : BigDecimal.ONE.setScale(3), row.getSubfamilyCode(),
                    row.getStock(), row.getRotation(), row.getByItm8(), false));
        }
        List<String> missing = keys.stream().filter(key -> !found.containsKey(key)).distinct().toList();
        if (!missing.isEmpty()) {
            productDrafts.findPendingByEans(storeId, missing).forEach(draft -> found.put(draft.getEan(), draft(draft)));
        }
        return found;
    }

    /**
     * Artykuł utworzony plikiem CADBEZP, którego nie ma jeszcze w kartotece (czeka na kolejny raport): jest już
     * w programie sklepu, więc wpisany EAN dostaje dane z pliku, a wiersz nie jest nowym artykułem.
     */
    private static CatalogProduct draft(ProductDraft draft) {
        Measure measure = Measure.of(draft.getUnit(), draft.getNetContent());
        return new CatalogProduct(draft.getEan(), null, draft.getEan(), draft.getName(), draft.getBrand(),
                draft.getPurchasePrice(), draft.getVatPercent(), draft.getVatPercent(), draft.getSalePrice(), false,
                null, null, null,
                measure != null ? measure.unit() : Measure.PIECE,
                measure != null ? measure.netContent() : BigDecimal.ONE.setScale(3), draft.getSubfamilyCode(), null,
                null, false, true);
    }

    /** EAN z GTIN-u faktury: same cyfry, GTIN-14 z zerem na początku jako EAN-13; {@code null} bez kodu. */
    static String ean(String gtin) {
        if (gtin == null) {
            return null;
        }
        String digits = gtin.replaceAll("\\D", "");
        if (digits.length() == 14 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return digits.isEmpty() || digits.chars().allMatch(c -> c == '0') ? null : digits;
    }

    /** Klucz porównania EAN-ów: bez zer wiodących (GTIN-14 = EAN-13). */
    static String key(String ean) {
        return ean == null ? null : ean.replaceFirst("^0+", "");
    }

    /** Stawka z opisu pozycji ("23%", "0% WDT", "zw.", "odwr. obc.") w procentach; nieznana = {@code null}. */
    static BigDecimal vatPercent(String rate) {
        if (rate == null) {
            return null;
        }
        Matcher matcher = RATE.matcher(rate.strip());
        if (matcher.find()) {
            return new BigDecimal(matcher.group(1).replace(',', '.'));
        }
        String lower = rate.strip().toLowerCase(Locale.ROOT);
        return lower.startsWith("zw") || lower.startsWith("np") || lower.startsWith("odwr") ? BigDecimal.ZERO : null;
    }

    /**
     * Cena jednostkowa netto; bez niej z brutto i stawki, a bez obu z wartości netto i ilości. Obowiązuje wartość netto
     * pozycji (P_11): gdy ilość × cena jej nie daje, cena jest przed rabatem (P_10: 10 × 21,40 z rabatem 1,50 = 199,00)
     * albo w korekcie zerowa, więc liczymy ją z wartości.
     */
    private static BigDecimal unitNet(KsefInvoiceReader.Line line, BigDecimal vat) {
        BigDecimal fromValue = line.net() != null && line.quantity() != null && line.quantity().signum() != 0
                ? line.net().divide(line.quantity(), 4, RoundingMode.HALF_UP) : null;
        if (line.unitNet() != null) {
            return fromValue != null && line.unitNet().multiply(line.quantity()).subtract(line.net()).abs()
                    .compareTo(VALUE_TOLERANCE) > 0 ? fromValue : line.unitNet();
        }
        if (line.unitGross() != null && vat != null) {
            return line.unitGross().multiply(HUNDRED).divide(HUNDRED.add(vat), 4, RoundingMode.HALF_UP);
        }
        return fromValue;
    }
}
