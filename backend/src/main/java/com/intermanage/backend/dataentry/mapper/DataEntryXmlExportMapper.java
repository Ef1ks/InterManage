package com.intermanage.backend.dataentry.mapper;

import org.mapstruct.Mapper;

import com.intermanage.backend.dataentry.dto.XmlExportItem;
import com.intermanage.backend.dataentry.repository.DataEntryXmlExportRepository;

@Mapper
public interface DataEntryXmlExportMapper {

    XmlExportItem toItem(DataEntryXmlExportRepository.Summary summary);
}
