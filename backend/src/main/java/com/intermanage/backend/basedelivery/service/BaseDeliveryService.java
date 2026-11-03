package com.intermanage.backend.basedelivery.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.basedelivery.component.BaseDeliveryParser;
import com.intermanage.backend.basedelivery.component.ShortDatesPdf;
import com.intermanage.backend.basedelivery.dto.BaseDeliveryLineResponse;
import com.intermanage.backend.basedelivery.dto.BaseDeliveryResponse;
import com.intermanage.backend.basedelivery.dto.BaseDeliveryRow;
import com.intermanage.backend.basedelivery.dto.BaseDeliverySummary;
import com.intermanage.backend.basedelivery.entity.BaseDelivery;
import com.intermanage.backend.basedelivery.entity.BaseDeliveryLine;
import com.intermanage.backend.basedelivery.mapper.BaseDeliveryMapper;
import com.intermanage.backend.basedelivery.repository.BaseDeliveryRepository;
import com.intermanage.backend.common.component.XlsReader;
import com.intermanage.backend.common.component.XlsxReader;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.common.exception.InvalidReportException;
import com.intermanage.backend.common.exception.InvalidWorkbookException;
import com.intermanage.backend.dataentry.dto.CatalogProduct;
import com.intermanage.backend.dataentry.service.DataEntryService;
import com.intermanage.backend.document.service.DocumentService;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.repository.ProductRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Dokumenty dostaw z bazy: wgranie pliku .xls (albo .xlsx) z bonem, lista wgranych i bon z liniami i artykułami
 * z kartoteki do tabeli jak we wprowadzaniu danych. Ponowne wgranie bonu zastępuje poprzednie (znacznik).
 */
@Service
public class BaseDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(BaseDeliveryService.class);
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private static final int ITM8_LENGTH = 13;
    /** Kontener OLE starego Excela (.xls). */
    private static final byte[] OLE_SIGNATURE = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1,
            (byte) 0xB1, 0x1A, (byte) 0xE1};
    /** Zip (.xlsx, .xlsm). */
    private static final byte[] ZIP_SIGNATURE = {0x50, 0x4B, 0x03, 0x04};

    private final BaseDeliveryRepository deliveries;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final ProductRepository products;
    private final DataEntryService dataEntry;
    private final BaseDeliveryParser parser;
    private final XlsReader xlsReader;
    private final XlsxReader xlsxReader;
    private final ShortDatesPdf shortDatesPdf;
    private final BaseDeliveryMapper mapper;

    public BaseDeliveryService(BaseDeliveryRepository deliveries, StoreRepository stores, AppUserRepository users,
            ProductRepository products, DataEntryService dataEntry, BaseDeliveryParser parser, XlsReader xlsReader,
            XlsxReader xlsxReader, ShortDatesPdf shortDatesPdf, BaseDeliveryMapper mapper) {
        this.deliveries = deliveries;
        this.stores = stores;
        this.users = users;
        this.products = products;
        this.dataEntry = dataEntry;
        this.parser = parser;
        this.xlsReader = xlsReader;
        this.xlsxReader = xlsxReader;
        this.shortDatesPdf = shortDatesPdf;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<BaseDeliverySummary> list(UUID storeId) {
        return deliveries.findTop30ByStoreIdAndReplacedAtIsNullOrderByDeliveryDateDescUploadedAtDesc(storeId).stream()
                .map(mapper::toSummary).toList();
    }

    /**
     * Wgranie bonu: plik musi być dla tego sklepu; ten sam bon wgrany ponownie dostaje nowy wpis, a poprzedni
     * znacznik zastąpienia.
     */
    @Transactional
    public BaseDeliveryResponse upload(UUID storeId, MultipartFile file, UUID userId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        String fileName = file.getOriginalFilename() == null ? "dostawa.xls" : file.getOriginalFilename();
        if (file.isEmpty()) {
            throw ApiException.badRequest("Wybierz plik do wgrania");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw ApiException.badRequest("Plik jest za duży (najwyżej 20 MB)");
        }
        BaseDeliveryParser.Parsed parsed = parse(file, fileName);
        if (!sameStore(parsed.storeNumber(), store.getNumber())) {
            throw ApiException.conflict("Plik jest dla sklepu " + parsed.storeNumber() + ", a wybrany sklep to "
                    + store.getNumber());
        }
        AppUser by = users.getReferenceById(userId);
        deliveries.findByStoreIdAndNumberAndReplacedAtIsNull(storeId, parsed.number()).ifPresent(previous -> {
            previous.replace(by);
            // Znacznik przed nowym wpisem: aktywny może być tylko jeden bon o tym numerze (indeks unikalny).
            deliveries.flush();
        });
        BaseDelivery delivery = new BaseDelivery(store, parsed.number(), parsed.deliveryDate(), fileName, by);
        int lineNo = 1;
        for (BaseDeliveryRow row : parsed.rows()) {
            delivery.addLine(new BaseDeliveryLine(delivery, lineNo++, row));
        }
        deliveries.saveAndFlush(delivery);
        return response(storeId, delivery);
    }

    @Transactional(readOnly = true)
    public BaseDeliveryResponse get(UUID storeId, UUID id) {
        return response(storeId, delivery(storeId, id));
    }

    /** Raport krótkich terminów całego bonu (bez względu na wybrane nośniki). */
    @Transactional(readOnly = true)
    public DocumentService.ExportFile shortDatesPdf(UUID storeId, UUID id) {
        BaseDelivery delivery = delivery(storeId, id);
        Store store = delivery.getStore();
        List<ShortDatesPdf.Line> lines = delivery.getLines().stream().filter(BaseDeliveryLine::isShortDated)
                .map(line -> new ShortDatesPdf.Line(line.getCarrier(), line.getItm8(), line.getEan(), line.getName(),
                        line.getQuantity(), line.getBestBefore(), line.getRequiredBestBefore()))
                .toList();
        byte[] content = shortDatesPdf.pdf(new ShortDatesPdf.Report(store.getNumber() + " " + store.getName(),
                delivery.getNumber(), delivery.getDeliveryDate(), lines));
        return new DocumentService.ExportFile("krotkie-terminy-" + delivery.getNumber() + ".pdf", "application/pdf",
                content);
    }

    private BaseDelivery delivery(UUID storeId, UUID id) {
        return deliveries.findByIdAndStoreIdAndReplacedAtIsNull(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu dostawy"));
    }

    /**
     * Bon z liniami i artykułami z kartoteki. Artykuł szukamy po ITM8 z pliku, a bez trafienia po EAN-ie (bieżącym,
     * z multikodów i dawnych par), bo baza wysyła część towaru pod własnym ITM8 (np. 7 UP: 61652855 w pliku,
     * 6893790 w kartotece). Dane artykułu jak we wprowadzaniu danych ({@link DataEntryService#lookup}).
     */
    private BaseDeliveryResponse response(UUID storeId, BaseDelivery delivery) {
        List<BaseDeliveryLine> lines = delivery.getLines();
        Map<String, Product> byItm8 = products
                .findByStoreIdAndItm8In(storeId, lines.stream().map(line -> catalogItm8(line.getItm8())).toList())
                .stream().collect(Collectors.toMap(Product::getItm8, Function.identity(), (first, second) -> first));
        Map<UUID, String> keys = new HashMap<>();
        for (BaseDeliveryLine line : lines) {
            Product product = byItm8.get(catalogItm8(line.getItm8()));
            String ean = product != null && product.getEan() != null ? product.getEan() : line.getEan();
            String key = key(ean);
            if (key != null) {
                keys.put(line.getId(), key);
            }
        }
        Map<String, CatalogProduct> catalog = dataEntry.lookup(storeId, Set.copyOf(keys.values())).stream()
                .collect(Collectors.toMap(CatalogProduct::ean, Function.identity(), (first, second) -> first));
        Set<String> catalogItm8s = catalog.values().stream().map(CatalogProduct::itm8).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, Short> deposits = new HashMap<>();
        products.findByStoreIdAndItm8In(storeId, catalogItm8s).forEach(product -> deposits.put(product.getItm8(),
                product.getDepositCode() == null ? 0 : product.getDepositCode()));

        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, BigDecimal> values = new HashMap<>();
        List<BaseDeliveryLineResponse> responses = new ArrayList<>(lines.size());
        for (BaseDeliveryLine line : lines) {
            String key = keys.get(line.getId());
            CatalogProduct product = key == null ? null : catalog.get(key);
            responses.add(mapper.toLine(line, product, product == null ? null : deposits.get(product.itm8())));
            counts.computeIfAbsent(line.getCarrier(), carrier -> new int[1])[0]++;
            values.merge(line.getCarrier(), line.getNetValue(), BigDecimal::add);
        }
        List<BaseDeliveryResponse.Carrier> carriers = counts.entrySet().stream()
                .map(entry -> new BaseDeliveryResponse.Carrier(entry.getKey(), entry.getValue()[0],
                        values.get(entry.getKey())))
                .toList();
        return mapper.toResponse(delivery, carriers, responses);
    }

    /** ITM8 z pliku (8 cyfr) tak, jak jest w kartotece (13 cyfr z zerami wiodącymi). */
    static String catalogItm8(String itm8) {
        return itm8.length() >= ITM8_LENGTH ? itm8 : "0".repeat(ITM8_LENGTH - itm8.length()) + itm8;
    }

    /** EAN jako klucz kartoteki: same cyfry bez zer wiodących (GTIN-14 z zerem = EAN-13). */
    private static String key(String ean) {
        if (ean == null) {
            return null;
        }
        String digits = ean.replaceAll("\\D", "").replaceFirst("^0+", "");
        return digits.isEmpty() ? null : digits;
    }

    private static boolean sameStore(String fileStore, String storeNumber) {
        return storeNumber != null && fileStore.replaceFirst("^0+", "").equals(storeNumber.replaceFirst("^0+", ""));
    }

    /**
     * Format rozpoznajemy po pierwszych bajtach, nie po nazwie: plik z końcówką .xls bywa w środku .xlsx (zip), a
     * Windows podaje typ (application/vnd.ms-excel) tylko po końcówce.
     */
    private BaseDeliveryParser.Parsed parse(MultipartFile file, String fileName) {
        try {
            byte[] bytes = file.getBytes();
            if (startsWith(bytes, OLE_SIGNATURE)) {
                return parser.parse(xlsReader.readFirstSheet(bytes));
            }
            if (startsWith(bytes, ZIP_SIGNATURE)) {
                return parser.parse(readXlsx(file));
            }
            log.warn("Nieznany format pliku dostawy z bazy {}: {}", fileName, HexFormat.of().formatHex(bytes, 0,
                    Math.min(bytes.length, 16)));
            throw ApiException.badRequest(looksLikeText(bytes)
                    ? "Plik " + fileName + " to nie Excel, tylko tekst (np. strona HTML zapisana jako .xls). "
                            + "Otwórz go w Excelu i zapisz jako skoroszyt .xlsx albo .xls."
                    : "Plik " + fileName + " to nie Excel (.xls ani .xlsx). Otwórz go w Excelu i zapisz jako "
                            + "skoroszyt .xlsx albo .xls.");
        } catch (InvalidReportException e) {
            throw ApiException.badRequest(e.getMessage())
                    .withProperty("rowErrors", e.errors())
                    .withProperty("rowErrorCount", e.errorCount());
        } catch (InvalidWorkbookException e) {
            throw ApiException.badRequest(e.getMessage());
        } catch (IOException e) {
            log.warn("Nie udało się odczytać pliku dostawy z bazy", e);
            throw ApiException.badRequest("Nie udało się odczytać pliku. Sprawdź, czy nie jest uszkodzony.");
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }

    /** Początek pliku bez bajtów sterujących poza tabulatorem i końcem linii (HTML, CSV, tekst z tabulatorami). */
    private static boolean looksLikeText(byte[] bytes) {
        int length = Math.min(bytes.length, 512);
        for (int i = 0; i < length; i++) {
            int b = bytes[i] & 0xFF;
            if (b < 0x09 || (b > 0x0D && b < 0x20)) {
                return false;
            }
        }
        return length > 0;
    }

    private List<List<String>> readXlsx(MultipartFile file) throws IOException, InvalidWorkbookException {
        Path temp = Files.createTempFile("base-delivery-", ".xlsx");
        try {
            file.transferTo(temp);
            List<List<String>> rows = new ArrayList<>();
            xlsxReader.readFirstSheet(temp, (rowNumber, cells) -> {
                while (rows.size() < rowNumber - 1) {
                    rows.add(List.of());
                }
                rows.add(cells);
            });
            return rows;
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
