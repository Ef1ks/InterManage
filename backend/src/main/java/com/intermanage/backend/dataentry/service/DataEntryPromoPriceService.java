package com.intermanage.backend.dataentry.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.dataentry.dto.PromoPriceAddRequest;
import com.intermanage.backend.dataentry.dto.PromoPriceItem;
import com.intermanage.backend.dataentry.entity.DataEntryPromoPrice;
import com.intermanage.backend.dataentry.mapper.DataEntryPromoPriceMapper;
import com.intermanage.backend.dataentry.repository.DataEntryPromoPriceRepository;

/**
 * Kolejka cen zwykłych artykułów w promocji do makra taryf (jak kolejka zamknięć faktur): dokumenty dokładają
 * artykuły, a makro przechodzi je wszystkie naraz. Nowa cena EAN-u, który jeszcze czeka, zastępuje poprzednią
 * (znacznik), więc ten sam artykuł z dwóch dokumentów nie idzie do makra dwa razy, a ta sama cena, która już jest na
 * liście (także wygenerowana), nie daje drugiego wpisu.
 */
@Service
public class DataEntryPromoPriceService {

    private final DataEntryPromoPriceRepository prices;
    private final DataEntryPromoPriceMapper mapper;
    private final AppUserRepository users;

    public DataEntryPromoPriceService(DataEntryPromoPriceRepository prices, DataEntryPromoPriceMapper mapper,
            AppUserRepository users) {
        this.prices = prices;
        this.mapper = mapper;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<PromoPriceItem> list(UUID storeId) {
        return prices.findByStoreIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(storeId).stream()
                .map(mapper::toItem).toList();
    }

    @Transactional
    public List<PromoPriceItem> add(UUID storeId, PromoPriceAddRequest request, UUID userId) {
        AppUser user = user(userId);
        Map<String, PromoPriceAddRequest.Item> byEan = new LinkedHashMap<>();
        request.items().forEach(item -> byEan.put(item.ean(), item));
        for (DataEntryPromoPrice old : prices.findByStoreIdAndEanInAndDeletedAtIsNull(storeId, byEan.keySet())) {
            PromoPriceAddRequest.Item item = byEan.get(old.getEan());
            if (item != null && old.getSalePrice().compareTo(item.salePrice()) == 0) {
                // Ta sama cena już jest na liście (np. ponowny zapis dokumentu): bez drugiego wpisu.
                byEan.remove(old.getEan());
            } else if (old.getGeneratedAt() == null) {
                old.delete(user);
            }
        }
        String document = request.document() == null || request.document().isBlank() ? null
                : request.document().strip();
        byEan.values().forEach(item -> prices.save(new DataEntryPromoPrice(storeId, item.ean(), item.itm8(),
                item.name().strip(), item.salePrice(), item.vatCode(), item.promoPrice(), item.promoTo(), document,
                user)));
        prices.flush();
        return list(storeId);
    }

    @Transactional
    public List<PromoPriceItem> markGenerated(UUID storeId, List<UUID> ids, UUID userId) {
        return change(storeId, ids, price -> price.markGenerated(user(userId)));
    }

    @Transactional
    public List<PromoPriceItem> delete(UUID storeId, List<UUID> ids, UUID userId) {
        return change(storeId, ids, price -> price.delete(user(userId)));
    }

    @Transactional
    public List<PromoPriceItem> restore(UUID storeId, List<UUID> ids) {
        return change(storeId, ids, DataEntryPromoPrice::restore);
    }

    private List<PromoPriceItem> change(UUID storeId, List<UUID> ids, Consumer<DataEntryPromoPrice> action) {
        prices.findByStoreIdAndIdIn(storeId, ids).forEach(action);
        prices.flush();
        return list(storeId);
    }

    private AppUser user(UUID userId) {
        return userId != null ? users.getReferenceById(userId) : null;
    }
}
