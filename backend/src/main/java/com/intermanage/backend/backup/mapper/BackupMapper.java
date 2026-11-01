package com.intermanage.backend.backup.mapper;

import java.time.Instant;
import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.backup.dto.BackupRunResponse;
import com.intermanage.backend.backup.dto.BackupSettingsResponse;
import com.intermanage.backend.backup.entity.BackupRun;
import com.intermanage.backend.backup.entity.BackupSettings;

@Mapper
public interface BackupMapper {

    @Mapping(target = "startedBy", source = "startedBy.displayName")
    BackupRunResponse toRunResponse(BackupRun run);

    List<BackupRunResponse> toRunResponses(List<BackupRun> runs);

    @Mapping(target = "googleConfigured", source = "googleConfigured")
    @Mapping(target = "connected", source = "settings.connected")
    @Mapping(target = "googleEmail", source = "settings.googleEmail")
    @Mapping(target = "googleConnectedAt", source = "settings.googleConnectedAt")
    @Mapping(target = "googleConnectedBy", source = "settings.googleConnectedBy.displayName")
    @Mapping(target = "enabled", source = "settings.enabled")
    @Mapping(target = "runHour", source = "settings.runHour")
    @Mapping(target = "keepCount", source = "settings.keepCount")
    @Mapping(target = "passwordSet", source = "settings.passwordSet")
    @Mapping(target = "passwordSetAt", source = "settings.passwordSetAt")
    @Mapping(target = "passwordSetBy", source = "settings.passwordSetBy.displayName")
    @Mapping(target = "running", source = "running")
    @Mapping(target = "nextRunAt", source = "nextRunAt")
    @Mapping(target = "runs", source = "runs")
    BackupSettingsResponse toResponse(BackupSettings settings, boolean googleConfigured, boolean running,
            Instant nextRunAt, List<BackupRun> runs);
}
