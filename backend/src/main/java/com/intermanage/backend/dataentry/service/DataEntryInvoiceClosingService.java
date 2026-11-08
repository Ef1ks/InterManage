package com.intermanage.backend.dataentry.service;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.dataentry.dto.InvoiceClosingItem;
import com.intermanage.backend.dataentry.entity.DataEntryInvoiceClosing;
import com.intermanage.backend.dataentry.entity.DataEntryXmlExport;
import com.intermanage.backend.dataentry.mapper.DataEntryInvoiceClosingMapper;
import com.intermanage.backend.dataentry.repository.DataEntryInvoiceClosingRepository;

/**
 * Kolejka faktur do zamknięcia w programie sklepu. Zapis pliku XML na dysku dodaje fakturę raz. Skrypt składa
 * przeglądarka, a serwer pamięta, co już do niego poszło. Wygenerowane da się skopiować ponownie, bo schowek łatwo
 * nadpisać, a usunięcie to znacznik.
 */
@Service
public class DataEntryInvoiceClosingService {

    /** Nazwa pliku to {@code <nr sklepu>_<kod dostawcy albo NIP>_<nazwa>_<nr faktury>.xml}; NIP ma 10 cyfr, kod mniej. */
    private static final Pattern SUPPLIER_CODE = Pattern.compile("^\\d+_(\\d{1,9})_");

    private final DataEntryInvoiceClosingRepository closings;
    private final DataEntryInvoiceClosingMapper mapper;
    private final AppUserRepository users;

    public DataEntryInvoiceClosingService(DataEntryInvoiceClosingRepository closings,
            DataEntryInvoiceClosingMapper mapper, AppUserRepository users) {
        this.closings = closings;
        this.mapper = mapper;
        this.users = users;
    }

    /** Plik zapisany na dysku: nowa faktura trafia do kolejki, a ta sama faktura nie daje drugiego wpisu. */
    @Transactional
    public void register(DataEntryXmlExport export, UUID userId) {
        String number = export.getDocumentNumber();
        if (number == null || number.isBlank()) {
            return;
        }
        String code = supplierCode(export.getFileName());
        closings.findByStoreIdAndSupplierCodeAndDocumentNumber(export.getStoreId(), code, number)
                .ifPresentOrElse(existing -> {
                    if (existing.getGeneratedAt() == null) {
                        existing.refresh(export.getId(), export.getSupplierName(), export.getDocumentDate(),
                                export.getNetValue());
                    }
                }, () -> closings.save(new DataEntryInvoiceClosing(export.getStoreId(), export.getId(), code, number,
                        export.getSupplierName(), export.getDocumentDate(), export.getNetValue(), user(userId))));
    }

    @Transactional(readOnly = true)
    public List<InvoiceClosingItem> list(UUID storeId) {
        return closings.findByStoreIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(storeId).stream().map(mapper::toItem)
                .toList();
    }

    @Transactional
    public List<InvoiceClosingItem> markGenerated(UUID storeId, List<UUID> ids, UUID userId) {
        return change(storeId, ids, closing -> closing.markGenerated(user(userId)));
    }

    @Transactional
    public List<InvoiceClosingItem> delete(UUID storeId, List<UUID> ids, UUID userId) {
        return change(storeId, ids, closing -> closing.delete(user(userId)));
    }

    @Transactional
    public List<InvoiceClosingItem> restore(UUID storeId, List<UUID> ids) {
        return change(storeId, ids, DataEntryInvoiceClosing::restore);
    }

    private List<InvoiceClosingItem> change(UUID storeId, List<UUID> ids, Consumer<DataEntryInvoiceClosing> action) {
        closings.findByStoreIdAndIdIn(storeId, ids).forEach(action);
        closings.flush();
        return list(storeId);
    }

    private AppUser user(UUID userId) {
        return userId != null ? users.getReferenceById(userId) : null;
    }

    static String supplierCode(String fileName) {
        Matcher matcher = SUPPLIER_CODE.matcher(fileName);
        return matcher.find() ? matcher.group(1) : "";
    }
}
