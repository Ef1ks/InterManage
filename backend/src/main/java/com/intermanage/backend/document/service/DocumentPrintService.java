package com.intermanage.backend.document.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.component.DocumentPrint;
import com.intermanage.backend.document.component.DocumentPrint.Detail;
import com.intermanage.backend.document.component.DocumentPrint.Heading;
import com.intermanage.backend.document.component.DocumentPrint.Line;
import com.intermanage.backend.document.component.DocumentPrint.Party;
import com.intermanage.backend.document.component.DocumentPrintPdf;
import com.intermanage.backend.document.component.DocumentPrintXlsx;
import com.intermanage.backend.document.dto.CorrectedDelivery;
import com.intermanage.backend.document.entity.DeliveryCorrection;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentLine;
import com.intermanage.backend.document.entity.DocumentTransferReceivedLine;
import com.intermanage.backend.document.entity.DocumentTransferTarget;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.document.repository.DocumentTransferReceivedLineRepository;
import com.intermanage.backend.document.repository.DocumentTransferTargetRepository;
import com.intermanage.backend.document.service.DocumentService.ExportFile;
import com.intermanage.backend.nomenclature.entity.Nomenclature;
import com.intermanage.backend.nomenclature.repository.NomenclatureRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Wydruki dokumentów magazynowych: PDF i Excel z tych samych danych ({@link DocumentPrint}). Przyjęcie PZ ma PDF
 * w układzie bonu z systemu sklepu ({@link DocumentService#deliveryNote}), a w Excelu linie po korektach. Sklep
 * docelowy ruchu międzymagazynowego drukuje przyjęcie: ten sam dokument w ilościach, które naprawdę przyjechały.
 * Ruch międzymagazynowy z przypiętymi poprawami drukuje się po nich, po obu stronach.
 */
@Service
public class DocumentPrintService {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Tytuł wydruku, początek nazwy pliku, podpisy i etykieta drugiej strony ({@code null}: bez niej). */
    private record Layout(String title, String fileName, List<String> signatures, String party) {
    }

    private final DocumentService documentService;
    private final DocumentRepository documents;
    private final DocumentTransferTargetRepository transferTargets;
    private final DocumentTransferReceivedLineRepository receivedLines;
    private final DeliveryCorrectionService corrections;
    private final SupplierRepository suppliers;
    private final NomenclatureRepository nomenclatures;
    private final AppUserRepository users;
    private final DocumentPrintPdf pdf;
    private final DocumentPrintXlsx xlsx;

    public DocumentPrintService(DocumentService documentService, DocumentRepository documents,
            DocumentTransferTargetRepository transferTargets, DocumentTransferReceivedLineRepository receivedLines,
            DeliveryCorrectionService corrections, SupplierRepository suppliers, NomenclatureRepository nomenclatures, AppUserRepository users,
            DocumentPrintPdf pdf, DocumentPrintXlsx xlsx) {
        this.documentService = documentService;
        this.documents = documents;
        this.transferTargets = transferTargets;
        this.receivedLines = receivedLines;
        this.corrections = corrections;
        this.suppliers = suppliers;
        this.nomenclatures = nomenclatures;
        this.users = users;
        this.pdf = pdf;
        this.xlsx = xlsx;
    }

    @Transactional(readOnly = true)
    public ExportFile pdf(UUID storeId, String typeCode, UUID id, UUID userId) {
        if (type(typeCode) == DocumentType.PZ) {
            return documentService.deliveryNote(storeId, typeCode, id, userId);
        }
        Document document = document(storeId, typeCode, id);
        return new ExportFile(fileName(document) + ".pdf", "application/pdf", pdf.pdf(print(document, userId)));
    }

    @Transactional(readOnly = true)
    public ExportFile xlsx(UUID storeId, String typeCode, UUID id, UUID userId) {
        Document document = document(storeId, typeCode, id);
        return new ExportFile(fileName(document) + ".xlsx", XLSX, xlsx.xlsx(print(document, userId)));
    }

    /** Wydruk przyjęcia w sklepie docelowym; {@code format}: {@code pdf} albo {@code xlsx}. */
    @Transactional(readOnly = true)
    public ExportFile incoming(UUID storeId, UUID targetId, String format, UUID userId) {
        DocumentTransferTarget target = transferTargets.findIncoming(storeId, targetId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
        Document document = document(target.getDocument().getStore().getId(), DocumentType.MM.name(),
                target.getDocument().getId());
        DocumentPrint print = incomingPrint(target, document, userId);
        String fileName = "przyjecie-miedzymagazynowe-" + document.getNumber();
        return switch (format) {
            case "pdf" -> new ExportFile(fileName + ".pdf", "application/pdf", pdf.pdf(print));
            case "xlsx" -> new ExportFile(fileName + ".xlsx", XLSX, xlsx.xlsx(print));
            default -> throw ApiException.notFound("Nieznany format pliku: " + format);
        };
    }

    private DocumentPrint incomingPrint(DocumentTransferTarget target, Document document, UUID userId) {
        Map<UUID, BigDecimal> received = receivedLines.findByTargetId(target.getId()).stream()
                .collect(Collectors.toMap(DocumentTransferReceivedLine::getDocumentLineId,
                        DocumentTransferReceivedLine::getQuantity));
        List<DeliveryCorrection> fixes = corrections.correctionsOf(List.of(document))
                .getOrDefault(document.getId(), List.of());
        List<Line> lines = fixes.isEmpty()
                ? document.getLines().stream()
                        .map(line -> received(line(line, true), received.get(line.getId()))).toList()
                : corrections.transferLines(document, fixes).stream()
                        .map(line -> received(line(line, true), received.get(UUID.fromString(line.key())))).toList();
        long differences = lines.stream().filter(line -> line.note() != null && line.note().startsWith("w dok"))
                .count();
        List<Detail> details = new ArrayList<>(target.getReceivedAt() == null
                ? List.of(new Detail("Przyjęte ilości", "nie wpisano"))
                : List.of(new Detail("Przyjęto", DATE.format(target.getReceivedAt().atZone(StoreTime.ZONE))),
                        new Detail("Różnic", String.valueOf(differences))));
        if (!fixes.isEmpty()) {
            details.add(new Detail("Po poprawach", numbers(fixes)));
        }
        Heading heading = new Heading("PRZYJĘCIE MIĘDZYMAGAZYNOWE", "Przyjęcie międzymagazynowe", document.getNumber(),
                document.getDocumentDate().toLocalDate(), store(target.getTargetStore(), null),
                store(document.getStore(), "Od sklepu"), details,
                List.of("Przyjął(ęła)", "Przewiózł(a)", "Wydał(a)"));
        return print(document, heading, lines, userId);
    }

    /**
     * Linia w przyjętej ilości: kwoty proporcjonalnie do niej, a ilość z dokumentu w opisie. Bez wpisu albo z tą samą
     * ilością zostaje linia z dokumentu; linia z ilością 0 nie ma z czego przeliczyć kwot.
     */
    private static Line received(Line line, BigDecimal arrived) {
        BigDecimal sent = line.quantity().abs();
        if (arrived == null || arrived.compareTo(sent) == 0) {
            return line;
        }
        boolean scalable = sent.signum() != 0;
        UnaryOperator<BigDecimal> scaled = value -> value == null || !scalable ? value
                : value.multiply(arrived).divide(sent, 2, RoundingMode.HALF_UP);
        return new Line(line.lineNumber(), line.itm8(), line.ean(), line.name(), line.brand(), line.supplier(),
                line.nomenclatureCode(), line.unit(), line.quantity().signum() < 0 ? arrived.negate() : arrived,
                line.vatRate(), line.purchasePrice(), scaled.apply(line.purchaseValue()), line.salePrice(),
                scaled.apply(line.saleValueNet()), scaled.apply(line.vatValue()), scaled.apply(line.saleValueGross()),
                "w dokumencie: " + sent.stripTrailingZeros().toPlainString().replace('.', ','));
    }

    private Document document(UUID storeId, String typeCode, UUID id) {
        return documents.findWithLines(storeId, type(typeCode), id)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
    }

    private static DocumentType type(String code) {
        return DocumentType.fromCode(code).orElseThrow(() -> ApiException.notFound("Nieznany typ dokumentu"));
    }

    private static String fileName(Document document) {
        return layout(document.getType()).fileName() + "-" + number(document);
    }

    private static String number(Document document) {
        return document.getType() == DocumentType.PZ ? document.bonNumber() : document.getNumber();
    }

    private static Layout layout(DocumentType type) {
        return switch (type) {
            case PZ -> new Layout("BON DOSTAWY", "bon-dostawy", List.of("Sporządził(a)", "Zatwierdził(a)"), "Dostawca");
            case PK -> new Layout("KOREKTA BŁĘDU SKLEPU", "korekta-bledu-sklepu",
                    List.of("Sporządził(a)", "Zatwierdził(a)"), "Dostawca");
            case WZ -> new Layout("KOREKTA OD DOSTAWCY", "korekta-od-dostawcy",
                    List.of("Sporządził(a)", "Zatwierdził(a)"), "Dostawca");
            case PL -> new Layout("PROTOKÓŁ LIKWIDACJI", "protokol-likwidacji",
                    List.of("Sporządził(a)", "Członek komisji", "Zatwierdził(a)"), null);
            case PPP -> new Layout("PRZENIESIENIE STANÓW", "przeniesienie-stanow",
                    List.of("Sporządził(a)", "Zatwierdził(a)"), null);
            case ZW -> new Layout("ZWROT DO DOSTAWCY", "zwrot-do-dostawcy",
                    List.of("Wydał(a)", "Odebrał(a) dla dostawcy"), "Dostawca");
            case MM -> new Layout("RUCH MIĘDZYMAGAZYNOWY", "ruch-miedzymagazynowy",
                    List.of("Wydał(a)", "Przewiózł(a)", "Przyjął(ęła)"), "Do sklepu");
            case RWPO -> new Layout("PROTOKÓŁ WYDANIA NA POTRZEBY SKLEPU", "protokol-potrzeby-sklepu",
                    List.of("Wydał(a)", "Odebrał(a)", "Zatwierdził(a)"), null);
            case RWREP -> new Layout("PROTOKÓŁ WYDANIA NA REPREZENTACJĘ", "protokol-reprezentacji",
                    List.of("Wydał(a)", "Odebrał(a)", "Zatwierdził(a)"), null);
            case RWDA -> new Layout("PROTOKÓŁ DAROWIZNY", "protokol-darowizny",
                    List.of("Przekazał(a)", "Odebrał(a) dla obdarowanego", "Zatwierdził(a)"), "Obdarowany");
        };
    }

    private DocumentPrint print(Document document, UUID userId) {
        return print(document, heading(document), lines(document), userId);
    }

    private DocumentPrint print(Document document, Heading heading, List<Line> lines, UUID userId) {
        UUID storeId = document.getStore().getId();
        List<String> codes = lines.stream().flatMap(line -> DocumentPrint.parentCodes(line.nomenclatureCode()).stream())
                .distinct().toList();
        Map<String, String> names = codes.isEmpty() ? Map.of()
                : nomenclatures.findByStoreIdAndCodeIn(storeId, codes).stream()
                        .collect(Collectors.toMap(Nomenclature::getCode, Nomenclature::getName, (a, b) -> a));
        String printedBy = userId != null ? users.findById(userId).map(AppUser::getDisplayName).orElse(null) : null;
        return DocumentPrint.of(heading, lines, names, printedBy);
    }

    private Heading heading(Document document) {
        Layout layout = layout(document.getType());
        List<Detail> details = new ArrayList<>();
        if (document.getSupplierDocumentNumber() != null) {
            details.add(new Detail("Nr dok. dostawcy", document.getSupplierDocumentNumber()));
        }
        Document corrected = corrections.deliveriesOf(List.of(document)).get(document.getId());
        if (corrected != null) {
            details.add(document.getType() == DocumentType.MM ? new Detail("Poprawia", corrected.getNumber())
                    : new Detail("Dotyczy przyjęcia", "bon " + corrected.bonNumber()));
        }
        if (document.getType() == DocumentType.MM) {
            List<DeliveryCorrection> fixes = corrections.correctionsOf(List.of(document))
                    .getOrDefault(document.getId(), List.of());
            if (!fixes.isEmpty()) {
                details.add(new Detail("Po poprawach", numbers(fixes)));
            }
        }
        return new Heading(layout.title(), document.getType().singular(), number(document),
                document.getDocumentDate().toLocalDate(), store(document.getStore(), null), party(document, layout),
                details, layout.signatures());
    }

    private static String numbers(List<DeliveryCorrection> fixes) {
        return fixes.stream().map(link -> link.getCorrection().getNumber()).collect(Collectors.joining(", "));
    }

    private Party party(Document document, Layout layout) {
        if (layout.party() == null) {
            return null;
        }
        return switch (document.getType()) {
            case MM -> transferTargets.findActive(document.getId())
                    .map(target -> store(target.getTargetStore(), layout.party()))
                    .orElse(new Party(layout.party(), null, List.of()));
            case RWDA -> new Party(layout.party(), null, List.of());
            default -> supplier(document, layout.party());
        };
    }

    private static Party store(Store store, String label) {
        return new Party(label, store.getNumber() + " · " + store.getName(), Stream.of(store.getAddress(),
                store.getNip() != null ? "NIP " + store.getNip() : null).filter(Objects::nonNull).toList());
    }

    /** Dane z listy dostawców; bez wspólnego dostawcy dokumentu pole zostaje puste, a dostawca jest przy liniach. */
    private Party supplier(Document document, String label) {
        if (document.getSupplierName() == null) {
            return new Party(label, null, List.of());
        }
        Supplier supplier = document.getSupplierCode() == null ? null
                : suppliers.findImported(document.getStore().getId()).stream()
                        .filter(found -> document.getSupplierCode().equals(found.getCode())).findFirst().orElse(null);
        List<String> lines = new ArrayList<>();
        if (supplier != null) {
            String city = Stream.of(supplier.getPostalCode(), supplier.getCity()).filter(Objects::nonNull)
                    .collect(Collectors.joining(" "));
            Stream.of(supplier.getAddress(), city.isEmpty() ? null : city,
                    supplier.getNip() != null ? "NIP " + supplier.getNip() : null)
                    .filter(Objects::nonNull).forEach(lines::add);
        }
        if (document.getSupplierCode() != null) {
            lines.add("kod " + document.getSupplierCode());
        }
        return new Party(label, supplier != null ? supplier.getName() : document.getSupplierName(), lines);
    }

    /**
     * Przyjęcie po przypiętych korektach (jak bon dostawy) i ruch międzymagazynowy po poprawach, reszta jak w raporcie;
     * wydania bez minusa przy zakupie.
     */
    private List<Line> lines(Document document) {
        boolean issue = document.getType().isIssue();
        List<DeliveryCorrection> fixes = corrections.correctionsOf(List.of(document))
                .getOrDefault(document.getId(), List.of());
        if (document.getType() == DocumentType.PZ || !fixes.isEmpty()) {
            List<Document> correctionDocuments = corrections.correctionDocuments(document.getStore().getId(), fixes);
            CorrectedDelivery corrected = corrections.corrected(document, correctionDocuments);
            return corrected.lines().stream().map(line -> line(line, issue)).toList();
        }
        return document.getLines().stream().map(line -> line(line, issue)).toList();
    }

    private static Line line(DocumentLine line, boolean issue) {
        return new Line(line.getLineNumber(), line.getItm8(), line.getEan(), line.getName(), line.getBrand(),
                line.getSupplierName(), line.getNomenclatureCode(), line.getUnit(), line.getQuantity(),
                line.getVatRate(), purchase(line.getPurchasePrice(), issue), purchase(line.getPurchaseValue(), issue),
                line.getSalePriceDocument(), line.getSaleValueNet(), line.getVatValue(), line.getSaleValueGross(), null);
    }

    private static BigDecimal purchase(BigDecimal value, boolean issue) {
        return value != null && issue ? value.negate() : value;
    }

    private static Line line(CorrectedDelivery.CorrectedLine line, boolean issue) {
        String note = !line.corrected() ? null : line.originalQuantity() == null ? "dopisane korektą" : "po korekcie";
        return new Line(line.lineNumber(), line.itm8(), line.ean(), line.name(), line.brand(), null,
                line.nomenclatureCode(), line.unit(), line.quantity(), line.vatRate(),
                purchase(line.purchasePrice(), issue), purchase(line.purchaseValue(), issue),
                line.salePriceDocument(), line.saleValueNet(), line.vatValue(), line.saleValueGross(), note);
    }
}
