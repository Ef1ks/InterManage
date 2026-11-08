package com.intermanage.backend.download.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.download.dto.DownloadResponse;
import com.intermanage.backend.download.entity.Download;
import com.intermanage.backend.download.repository.DownloadFileRepository;

@Mapper
public interface DownloadMapper {

    @Mapping(target = "id", source = "download.id")
    @Mapping(target = "title", source = "download.title")
    @Mapping(target = "description", source = "download.description")
    @Mapping(target = "file", source = "file")
    DownloadResponse toResponse(Download download, DownloadFileRepository.Summary file);

    DownloadResponse.FileInfo toFileInfo(DownloadFileRepository.Summary file);
}
