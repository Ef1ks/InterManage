package com.intermanage.backend.collector.service;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.collector.dto.CollectorFileDtos;
import com.intermanage.backend.collector.dto.CollectorListDetails;
import com.intermanage.backend.collector.entity.CollectorFile;
import com.intermanage.backend.collector.entity.CollectorFileLine;
import com.intermanage.backend.collector.entity.CollectorListOutput;
import com.intermanage.backend.collector.repository.CollectorFileLineRepository;
import com.intermanage.backend.collector.repository.CollectorFileRepository;
import com.intermanage.backend.common.component.XlsxWriter;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.component.InventoryFile;
import com.intermanage.backend.dataentry.component.OrderFile;
import com.intermanage.backend.dataentry.component.PriceControlFile;

/**
 * Inwentaryzacje i zamówienia z kolektora w historii. „Zrób teraz” zapisuje plik dla programu sklepu od razu (pozycja
 * wgrana), „Na przyszłość” zostawia ją czekającą, a plik powstaje z historii w dniu, w którym ma trafić do Mercalys.
 * Pozycja zapamiętuje artykuły z chwili utworzenia, więc później zmienione listy kolektora jej nie zmieniają.
 */
@Service
public class CollectorFileService {

    private static final int HISTORY = 200;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.of("Europe/Warsaw"));

    public record StoredFile(String fileName, byte[] content) {
    }

    private final CollectorFileRepository files;
    private final CollectorFileLineRepository lines;
    private final CollectorListService lists;
    private final AppUserRepository users;

    public CollectorFileService(CollectorFileRepository files, CollectorFileLineRepository lines,
            CollectorListService lists, AppUserRepository users) {
        this.files = files;
        this.lines = lines;
        this.lists = lists;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<CollectorFileDtos.Response> list(UUID storeId) {
        List<CollectorFile> found = files.findLatest(storeId, Limit.of(HISTORY));
        Map<UUID, Long> counts = found.isEmpty() ? Map.of()
                : files.countLines(found.stream().map(CollectorFile::getId).toList()).stream().collect(
                        Collectors.toMap(CollectorFileRepository.LineCount::getFileId,
                                CollectorFileRepository.LineCount::getLines));
        return found.stream().map(file -> response(file, counts.getOrDefault(file.getId(), 0L))).toList();
    }

    @Transactional(readOnly = true)
    public long countWaiting(UUID storeId, CollectorFile.Kind kind) {
        return files.countWaiting(storeId, kind);
    }

    @Transactional
    public CollectorFileDtos.Created create(UUID storeId, CollectorFileDtos.CreateRequest request, UUID actorId) {
        List<UUID> listIds = request.listIds() == null ? List.of() : request.listIds();
        boolean fromLists = !listIds.isEmpty();
        if (!fromLists && (request.kind() != CollectorFile.Kind.INVENTORY || request.items() == null)) {
            throw ApiException.badRequest("Wybierz listę z kolektora");
        }
        CollectorListDetails details = fromLists ? lists.details(storeId, listIds)
                : new CollectorListDetails(List.of(), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        List<CollectorListService.Item> items = new ArrayList<>();
        int skipped = 0;
        if (!fromLists) {
            for (CollectorFileDtos.Item item : request.items()) {
                if (PriceControlFile.ean(item.ean()) == null || item.quantity().signum() < 0) {
                    skipped++;
                    continue;
                }
                items.add(new CollectorListService.Item(item.itm8(), item.ean(), item.name(), item.brand(),
                        item.quantity()));
            }
        } else if (request.kind() == CollectorFile.Kind.INVENTORY) {
            for (CollectorListDetails.Row row : details.rows()) {
                if (PriceControlFile.ean(row.ean()) == null || row.quantity().signum() < 0) {
                    skipped++;
                    continue;
                }
                items.add(new CollectorListService.Item(row.itm8(), row.ean(), row.name(), row.brand(), row.quantity()));
            }
        } else {
            Map<String, CollectorListDetails.Row> byEan = new HashMap<>();
            details.rows().forEach(row -> byEan.putIfAbsent(stripZeros(row.ean()), row));
            for (CollectorFileDtos.OrderLine line : request.lines() == null ? List.<CollectorFileDtos.OrderLine>of()
                    : request.lines()) {
                if (PriceControlFile.ean(line.ean()) == null) {
                    throw ApiException.badRequest("Kod " + line.ean() + " nie jest EAN-em");
                }
                int cartons = line.quantity().intValue();
                if (line.quantity().stripTrailingZeros().scale() > 0 || cartons < 1
                        || cartons > OrderFile.MAX_QUANTITY) {
                    throw ApiException.badRequest("Ilość w zamówieniu to od 1 do " + OrderFile.MAX_QUANTITY
                            + " kartonów (EAN " + line.ean() + ")");
                }
                CollectorListDetails.Row row = byEan.get(stripZeros(line.ean()));
                items.add(new CollectorListService.Item(row == null ? null : row.itm8(), line.ean(),
                        row == null ? line.ean() : row.name(), row == null ? null : row.brand(), line.quantity()));
            }
        }
        if (items.isEmpty()) {
            throw ApiException.badRequest(request.kind() == CollectorFile.Kind.INVENTORY
                    ? "Na liście nie ma artykułów z EAN-em do inwentaryzacji" : "Zamówienie nie ma artykułów");
        }
        AppUser actor = users.getReferenceById(actorId);
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        CollectorFile file = files.saveAndFlush(new CollectorFile(storeId, request.kind(), note, actor));
        int position = 1;
        for (CollectorListService.Item item : items) {
            lines.save(new CollectorFileLine(file.getId(), position++, blankToNull(item.itm8()), item.ean(),
                    cut(item.name(), 200), item.brand() == null ? null : cut(item.brand(), 100), item.quantity()));
        }
        if (fromLists) {
            listIds.stream().distinct().forEach(listId -> files.addSource(file.getId(), listId));
            CollectorListOutput output = request.kind() == CollectorFile.Kind.INVENTORY
                    ? CollectorListOutput.INVENTORY : CollectorListOutput.ORDER;
            lists.markSaved(storeId, output, listIds, "Historia: " + DAY.format(file.getCreatedAt()), actorId);
        }
        return new CollectorFileDtos.Created(response(file, items.size()), skipped);
    }

    /** Plik dla programu sklepu z artykułów z historii; nazwa z chwili pobrania. */
    @Transactional(readOnly = true)
    public StoredFile file(UUID storeId, UUID id, String username) {
        CollectorFile file = find(storeId, id);
        List<CollectorFileLine> found = files.findLines(id);
        if (file.getKind() == CollectorFile.Kind.INVENTORY) {
            return new StoredFile(InventoryFile.fileName(username), InventoryFile.content(found.stream()
                    .map(line -> new InventoryFile.Line(line.getEan(), line.getQuantity())).toList()));
        }
        return new StoredFile(OrderFile.fileName(username), OrderFile.content(found.stream()
                .map(line -> new OrderFile.Line(line.getEan(), line.getQuantity().intValue())).toList()));
    }

    /** Plik zapisany dla programu sklepu: pozycja jest wgrana (ponowny zapis zmienia nazwę pliku). */
    @Transactional
    public CollectorFileDtos.Response loaded(UUID storeId, UUID id, String fileName, UUID actorId) {
        CollectorFile file = find(storeId, id);
        file.loaded(fileName.strip(), users.getReferenceById(actorId));
        files.flush();
        return response(file, files.findLines(id).size());
    }

    @Transactional(readOnly = true)
    public CollectorListDetails details(UUID storeId, UUID id) {
        find(storeId, id);
        return lists.detailsOfItems(storeId, files.findLines(id).stream()
                .map(line -> new CollectorListService.Item(line.getItm8(), line.getEan(), line.getName(),
                        line.getBrand(), line.getQuantity()))
                .toList());
    }

    @Transactional(readOnly = true)
    public byte[] xlsx(UUID storeId, UUID id) {
        CollectorFile file = find(storeId, id);
        List<XlsxWriter.Row> intro = new ArrayList<>();
        intro.add(XlsxWriter.Row.of(XlsxWriter.Cell.bold(title(file))));
        intro.add(XlsxWriter.Row.of("Sporządzono", made(file)));
        intro.add(XlsxWriter.Row.of("Stan", status(file)));
        if (file.getNote() != null) {
            intro.add(XlsxWriter.Row.of("Notatka", file.getNote()));
        }
        return lists.xlsxOf(title(file), intro, details(storeId, id));
    }

    @Transactional(readOnly = true)
    public byte[] pdf(UUID storeId, UUID id, String printedBy) {
        CollectorFile file = find(storeId, id);
        List<String> signatures = file.getKind() == CollectorFile.Kind.INVENTORY
                ? List.of("Liczył", "Sprawdził") : List.of("Sporządził", "Zatwierdził");
        return lists.pdfOf(storeId, title(file), "sporządzono " + made(file),
                status(file) + (file.getNote() == null ? "" : " · " + file.getNote()), details(storeId, id), printedBy,
                signatures);
    }

    /** „27.11.2026 09:41 z kolektora” albo „… z tabeli”, gdy pozycja nie powstała z list kolektora. */
    private String made(CollectorFile file) {
        return DAY.format(file.getCreatedAt()) + (files.countSources(file.getId()) > 0 ? " z kolektora" : " z tabeli")
                + (file.getCreatedBy() == null ? "" : ", " + file.getCreatedBy().getDisplayName());
    }

    private static String status(CollectorFile file) {
        String loaded = file.getKind() == CollectorFile.Kind.INVENTORY ? "Wgrana" : "Wgrane";
        return file.getStatus() == CollectorFile.Status.LOADED
                ? loaded + " do Mercalys " + DAY.format(file.getLoadedAt())
                        + (file.getLoadedBy() == null ? "" : ", " + file.getLoadedBy().getDisplayName())
                : "Czeka na wgranie do Mercalys";
    }

    @Transactional
    public void remove(UUID storeId, UUID id, UUID actorId) {
        find(storeId, id).markDeleted(users.getReferenceById(actorId));
    }

    @Transactional
    public void restore(UUID storeId, UUID id) {
        files.findById(id).filter(file -> file.getStoreId().equals(storeId) && file.getDeletedAt() != null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono pozycji historii"))
                .restore();
    }

    private static String title(CollectorFile file) {
        return file.getKind() == CollectorFile.Kind.INVENTORY ? "Inwentaryzacja" : "Zamówienie";
    }

    private CollectorFile find(UUID storeId, UUID id) {
        return files.findById(id).filter(file -> file.getStoreId().equals(storeId) && file.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono pozycji historii"));
    }

    private static CollectorFileDtos.Response response(CollectorFile file, long lineCount) {
        return new CollectorFileDtos.Response(file.getId(), file.getKind(), file.getStatus(), file.getNote(),
                file.getFileName(), file.getCreatedAt(),
                file.getCreatedBy() == null ? null : file.getCreatedBy().getDisplayName(), file.getLoadedAt(),
                file.getLoadedBy() == null ? null : file.getLoadedBy().getDisplayName(), lineCount);
    }

    private static String stripZeros(String ean) {
        return ean == null ? "" : ean.replaceFirst("^0+", "");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String cut(String value, int max) {
        String text = value == null ? "" : value.strip();
        return text.length() > max ? text.substring(0, max) : text;
    }
}
