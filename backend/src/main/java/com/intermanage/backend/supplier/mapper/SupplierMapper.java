package com.intermanage.backend.supplier.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.supplier.dto.MercalysSupplierCreationResponse;
import com.intermanage.backend.supplier.dto.SupplierContactResponse;
import com.intermanage.backend.supplier.dto.SupplierDetails;
import com.intermanage.backend.supplier.dto.SupplierImportResponse;
import com.intermanage.backend.supplier.dto.SupplierResponse;
import com.intermanage.backend.supplier.entity.MercalysSupplierCreation;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.entity.SupplierContact;
import com.intermanage.backend.supplier.entity.SupplierImport;
import com.intermanage.backend.supplier.entity.SupplierProfile;

@Mapper
public interface SupplierMapper {

    /** {@code profile} jest pusty, dopóki nikt w firmie nie uzupełnił danych dostawcy. */
    @Mapping(target = "id", source = "supplier.id")
    @Mapping(target = "name", source = "supplier.name")
    @Mapping(target = "website", source = "profile.website")
    @Mapping(target = "phone", source = "profile.phone")
    @Mapping(target = "email", source = "profile.email")
    SupplierResponse toResponse(Supplier supplier, SupplierProfile profile, int contactCount,
            SupplierContact representative);

    @Mapping(target = "id", source = "supplier.id")
    @Mapping(target = "createdAt", source = "supplier.createdAt")
    @Mapping(target = "listReportGeneratedAt", source = "supplier.lastImport.reportGeneratedAt")
    @Mapping(target = "createdBy", source = "supplier.createdBy.displayName")
    @Mapping(target = "website", source = "profile.website")
    @Mapping(target = "email", source = "profile.email")
    @Mapping(target = "phone", source = "profile.phone")
    @Mapping(target = "notes", source = "profile.notes")
    @Mapping(target = "detailsUpdatedAt", source = "profile.updatedAt")
    @Mapping(target = "detailsUpdatedBy", source = "profile.updatedBy.displayName")
    SupplierDetails toDetails(Supplier supplier, SupplierProfile profile, List<SupplierContactResponse> contacts,
            List<String> sharedWithStores);

    @Mapping(target = "updatedBy", source = "updatedBy.displayName")
    SupplierContactResponse toResponse(SupplierContact contact);

    @Mapping(target = "createdBy", source = "createdBy.displayName")
    MercalysSupplierCreationResponse toResponse(MercalysSupplierCreation creation);

    /** {@code importedBy} jest pusty, gdy konto osoby importującej zostało usunięte. */
    @Mapping(target = "importedBy", source = "importedBy.displayName")
    SupplierImportResponse toResponse(SupplierImport supplierImport);
}
