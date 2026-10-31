package com.intermanage.backend.dataentry.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.XlsxWriter;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.component.Measure;
import com.intermanage.backend.dataentry.dto.CatalogProduct;
import com.intermanage.backend.dataentry.dto.DataEntryInvoice;
import com.intermanage.backend.dataentry.dto.DataEntryLine;
import com.intermanage.backend.dataentry.dto.ExportRequest;
import com.intermanage.backend.dataentry.dto.NomenclatureItem;
import com.intermanage.backend.dataentry.entity.DataEntrySuggestion;
import com.intermanage.backend.dataentry.repository.DataEntryCatalogRepository;
import com.intermanage.backend.dataentry.repository.DataEntrySuggestionRepository;
import com.intermanage.backend.ksef.component.KsefInvoiceReader;
import com.intermanage.backend.ksef.dto.KsefInvoiceSummary;
import com.intermanage.backend.ksef.service.KsefInvoiceService;
import com.intermanage.backend.nomenclature.entity.Nomenclature;
import com.intermanage.backend.nomenclature.repository.NomenclatureRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Wprowadzanie danych: pozycje faktury z KSeF dopasowane po EAN-ie do kartoteki sklepu, wyszukiwanie artykułów po
 * EAN-ie dla wierszy wpisanych ręcznie, nomenklatura sklepu i eksport tabeli do Excela (wersja testowa).
 */
@Service
public class DataEntryService {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Pattern RATE = Pattern.compile("^(\\d+(?:[.,]\\d+)?)\\s*%");
    private static final Set<String> PIECE_UNITS = Set.of("szt", "szt.", "sztuka", "sztuki", "sz");
    private static final Set<String> KG_UNITS = Set.of("kg", "kg.", "kilogram");

    private final KsefInvoiceService ksefInvoices;
    private final KsefInvoiceReader reader;
    private final DataEntryCatalogRepository catalog;
    private final NomenclatureRepository nomenclatures;
    private final DataEntrySuggestionRepository suggestions;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final XlsxWriter xlsxWriter;

    public DataEntryService(KsefInvoiceService ksefInvoices, KsefInvoiceReader reader,
            DataEntryCatalogRepository catalog, NomenclatureRepository nomenclatures,
            DataEntrySuggestionRepository suggestions, StoreRepository stores, AppUserRepository users,
            XlsxWriter xlsxWriter) {
        this.ksefInvoices = ksefInvoices;
        this.reader = reader;
        this.catalog = catalog;
        this.nomenclatures = nomenclatures;
        this.suggestions = suggestions;
        this.stores = stores;
        this.users = users;
        this.xlsxWriter = xlsxWriter;
    }

    public record ExportFile(String fileName, byte[] content) {
    }

    /**
     * Pozycje faktury (XML z bazy, za pierwszym razem z KSeF w granicach limitu pobrań) z artykułami z kartoteki.
     * Faktura musi być widoczna w sklepie (IDWew), inaczej 404.
     */
    @Transactional
    public DataEntryInvoice invoice(UUID storeId, UUID invoiceId) {
        KsefInvoiceSummary summary = ksefInvoices.summary(storeId, invoiceId);
        KsefInvoiceReader.Invoice invoice;
        try {
            invoice = reader.read(ksefInvoices.xml(storeId, invoiceId).content());
        } catch (KsefInvoiceReader.InvalidInvoiceException e) {
            throw ApiException.badRequest("Nie udało się odczytać faktury z KSeF: " + e.getMessage());
        }
        List<KsefInvoiceReader.Line> lines = invoice.lines().stream().filter(line -> !line.beforeCorrection())
                .toList();
        Map<String, CatalogProduct> known = lookupByKey(storeId,
                lines.stream().map(line -> key(ean(line.gtin()))).filter(Objects::nonNull).toList());
        List<DataEntryLine> rows = lines.stream().map(line -> {
            String ean = ean(line.gtin());
            BigDecimal vat = vatPercent(line.rate());
            CatalogProduct product = ean != null ? known.get(key(ean)) : null;
            Measure measure = product != null ? Measure.of(product.unit(), product.netContent())
                    : Measure.fromInvoice(line.name(), line.unit());
            if (measure == null) {
                measure = Measure.piece();
            }
            return new DataEntryLine(line.number(), ean, line.quantity(), unitNet(line, vat),
                    line.name() != null ? line.name().strip() : null, line.unit(), vat, product, measure.unit(),
                    measure.netContent());
        }).toList();
        return new DataEntryInvoice(summary.id(), summary.invoiceNumber(), summary.sellerName(), summary.sellerNip(),
                summary.issueDate(), rows);
    }

    /** Artykuły z kartoteki dla wpisanych EAN-ów (bez zer wiodących); nieznane EAN-y pomija. */
    @Transactional(readOnly = true)
    public List<CatalogProduct> lookup(UUID storeId, Collection<String> eans) {
        List<String> keys = eans.stream().map(DataEntryService::ean).map(DataEntryService::key)
                .filter(Objects::nonNull).distinct().limit(500).toList();
        return List.copyOf(lookupByKey(storeId, keys).values());
    }

    /** Cała nomenklatura sklepu (rejony, rodziny, podrodziny) w kolejności kodów. */
    @Transactional(readOnly = true)
    public List<NomenclatureItem> nomenclature(UUID storeId) {
        return nomenclatures.findAllByStore(storeId).stream()
                .map(item -> new NomenclatureItem(item.getCode(), item.getName())).toList();
    }

    /**
     * Tabela w pliku Excel: kolumny jak na ekranie, wartość netto i marża przeliczone, nomenklatura z nazwami.
     * Wiersze z propozycji AI zapisują wyeksportowane wartości przy propozycji (co ludzie poprawiają).
     */
    @Transactional
    public ExportFile export(UUID storeId, ExportRequest request, UUID userId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        Map<String, String> names = nomenclatures.findAllByStore(storeId).stream()
                .collect(Collectors.toMap(Nomenclature::getCode, Nomenclature::getName, (a, b) -> a));

        List<XlsxWriter.Column> columns = List.of(
                new XlsxWriter.Column("EAN", 16), new XlsxWriter.Column("Ilość", 9),
                new XlsxWriter.Column("Cena netto", 11), new XlsxWriter.Column("Nazwa", 32),
                new XlsxWriter.Column("Marka", 16), new XlsxWriter.Column("Wartość netto", 13),
                new XlsxWriter.Column("VAT %", 7), new XlsxWriter.Column("Cena sprzedaży", 14),
                new XlsxWriter.Column("Marża %", 9), new XlsxWriter.Column("Rejon", 24),
                new XlsxWriter.Column("Rodzina", 28), new XlsxWriter.Column("Podrodzina", 30),
                new XlsxWriter.Column("Rodzaj sprzedaży", 10), new XlsxWriter.Column("Waga", 9),
                new XlsxWriter.Column("Nomenklatura", 16));
        List<XlsxWriter.Row> rows = new ArrayList<>();
        for (ExportRequest.Row row : request.rows()) {
            String subfamily = row.subfamilyCode() != null && row.subfamilyCode().length() == 6
                    ? row.subfamilyCode() : null;
            BigDecimal value = row.quantity() != null && row.unitNet() != null
                    ? row.quantity().multiply(row.unitNet()).setScale(2, RoundingMode.HALF_UP) : null;
            rows.add(XlsxWriter.Row.of(
                    row.ean(), row.quantity(), row.unitNet() != null ? XlsxWriter.Cell.money(row.unitNet()) : null,
                    row.name(), row.brand(), value != null ? XlsxWriter.Cell.money(value) : null,
                    row.vatPercent(), row.salePrice() != null ? XlsxWriter.Cell.money(row.salePrice()) : null,
                    marginPercent(row), level(names, subfamily, 2), level(names, subfamily, 4),
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

    private void saveExported(UUID storeId, List<ExportRequest.Row> rows, UUID userId) {
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
     * faktury w sztukach, a w kg razy waga sztuki sprzedawanej w kg; inna jednostka na fakturze (karton, opakowanie)
     * nie daje marży.
     */
    static BigDecimal marginPercent(ExportRequest.Row row) {
        BigDecimal purchase = purchasePerPiece(row.unitNet(), row.invoiceUnit(), row.unit(), row.netContent());
        if (purchase == null || row.salePrice() == null || row.salePrice().signum() <= 0 || row.vatPercent() == null) {
            return null;
        }
        BigDecimal saleNet = row.salePrice().multiply(HUNDRED)
                .divide(HUNDRED.add(row.vatPercent()), 6, RoundingMode.HALF_UP);
        return saleNet.subtract(purchase).multiply(HUNDRED).divide(saleNet, 1, RoundingMode.HALF_UP);
    }

    static BigDecimal purchasePerPiece(BigDecimal unitNet, String invoiceUnit, String saleUnit,
            BigDecimal netContent) {
        if (unitNet == null) {
            return null;
        }
        String unit = invoiceUnit == null ? "szt." : invoiceUnit.strip().toLowerCase(Locale.ROOT);
        if (PIECE_UNITS.contains(unit)) {
            return unitNet;
        }
        if (KG_UNITS.contains(unit)) {
            return Measure.KG.equals(saleUnit) && netContent != null ? unitNet.multiply(netContent) : unitNet;
        }
        return null;
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
        if (keys.isEmpty()) {
            return Map.of();
        }
        Map<String, CatalogProduct> found = new HashMap<>();
        for (DataEntryCatalogRepository.CatalogProduct row : catalog.findByEans(storeId,
                keys.stream().distinct().toArray(String[]::new))) {
            Measure measure = Measure.of(row.getUnit(), row.getNetContent());
            found.put(row.getKey(), new CatalogProduct(row.getKey(), row.getItm8(), row.getName(), row.getBrand(),
                    row.getSalePrice(), measure != null ? measure.unit() : Measure.PIECE,
                    measure != null ? measure.netContent() : BigDecimal.ONE.setScale(3), row.getSubfamilyCode()));
        }
        return found;
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

    /** Cena jednostkowa netto; bez niej z brutto i stawki, a bez obu z wartości netto i ilości. */
    private static BigDecimal unitNet(KsefInvoiceReader.Line line, BigDecimal vat) {
        if (line.unitNet() != null) {
            return line.unitNet();
        }
        if (line.unitGross() != null && vat != null) {
            return line.unitGross().multiply(HUNDRED).divide(HUNDRED.add(vat), 4, RoundingMode.HALF_UP);
        }
        if (line.net() != null && line.quantity() != null && line.quantity().signum() != 0) {
            return line.net().divide(line.quantity(), 4, RoundingMode.HALF_UP);
        }
        return null;
    }
}
