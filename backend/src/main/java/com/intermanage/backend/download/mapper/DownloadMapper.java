package com.intermanage.backend.download.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.download.dto.DownloadResponse;
import com.intermanage.backend.download.entity.Download;
import com.intermanage.backend.download.entity.DownloadFileChange;
import com.intermanage.backend.download.repository.DownloadFileRepository;

@Mapper
public interface DownloadMapper {

    @Mapping(target = "id", source = "download.id")
    @Mapping(target = "title", source = "download.title")
    @Mapping(target = "description", source = "download.description")
    @Mapping(target = "file", source = "file")
    @Mapping(target = "newVersion", source = "newVersion")
    DownloadResponse toResponse(Download download, DownloadResponse.FileInfo file, boolean newVersion);

    @Mapping(target = "changes", source = "changes")
    DownloadResponse.FileInfo toFileInfo(DownloadFileRepository.Summary file, List<DownloadFileChange> changes);

    DownloadResponse.Change toChange(DownloadFileChange change);
}
