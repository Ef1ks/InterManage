package com.intermanage.backend.store.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.store.dto.SidebarOrderResponse;
import com.intermanage.backend.store.entity.StoreSidebarSettings;
import com.intermanage.backend.store.repository.StoreRepository;
import com.intermanage.backend.store.repository.StoreSidebarSettingsRepository;

/** Kolejność kategorii menu bocznego, wspólna dla całego sklepu. */
@Service
public class StoreSidebarService {

    private final StoreSidebarSettingsRepository settings;
    private final StoreRepository stores;
    private final AppUserRepository users;

    public StoreSidebarService(StoreSidebarSettingsRepository settings, StoreRepository stores,
            AppUserRepository users) {
        this.settings = settings;
        this.stores = stores;
        this.users = users;
    }

    /** Zapisane kolejności sklepów; idą w {@code /auth/me}, żeby menu miało właściwy układ od pierwszego malowania. */
    @Transactional(readOnly = true)
    public Map<UUID, List<String>> orders(Collection<UUID> storeIds) {
        return settings.findByStoreIdIn(storeIds).stream()
                .collect(Collectors.toMap(saved -> saved.getStore().getId(), StoreSidebarSettings::getGroupOrder));
    }

    @Transactional
    public SidebarOrderResponse save(UUID storeId, List<String> groups, UUID userId) {
        List<String> order = groups.stream().distinct().toList();
        StoreSidebarSettings saved = settings.findByStoreId(storeId)
                .orElseGet(() -> new StoreSidebarSettings(stores.getReferenceById(storeId)));
        saved.update(order, users.getReferenceById(userId));
        settings.save(saved);
        return new SidebarOrderResponse(order);
    }
}
