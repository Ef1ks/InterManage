package com.intermanage.backend.store.mapper;

import java.util.UUID;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.store.dto.ProtoSettingsResponse;
import com.intermanage.backend.store.dto.StorePartnerResponse;
import com.intermanage.backend.store.dto.StoreResponse;
import com.intermanage.backend.store.dto.StoreSummary;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.entity.StorePartner;
import com.intermanage.backend.store.entity.StoreProtoSettings;

@Mapper
public interface StoreMapper {

    StoreResponse toResponse(Store store, long userCount);

    StoreSummary toSummary(Store store);

    /** Znajomość od strony sklepu {@code storeId}: drugi sklep pary i czy to ten sklep zapraszał. */
    @Mapping(target = "id", source = "partner.id")
    @Mapping(target = "store", expression = "java(toSummary(partner.otherThan(storeId)))")
    @Mapping(target = "sent", expression = "java(partner.getStore().getId().equals(storeId))")
    @Mapping(target = "invitedBy", source = "partner.invitedBy.displayName")
    @Mapping(target = "acceptedBy", source = "partner.acceptedBy.displayName")
    StorePartnerResponse toPartnerResponse(StorePartner partner, UUID storeId);

    /** {@code effectivePath}: folder ustawiony w sklepie albo domyślny. */
    @Mapping(target = "folderPath", source = "effectivePath")
    @Mapping(target = "defaultFolder", source = "defaultFolder")
    @Mapping(target = "hiddenProtocols", source = "settings.hiddenProtocols")
    @Mapping(target = "updatedBy", source = "settings.updatedBy.displayName")
    @Mapping(target = "updatedAt", source = "settings.updatedAt")
    ProtoSettingsResponse toProtoSettingsResponse(StoreProtoSettings settings, String effectivePath,
            boolean defaultFolder);
}
