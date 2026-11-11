package com.intermanage.backend.auth.mapper;

import org.mapstruct.Mapper;

import com.intermanage.backend.auth.dto.DashboardSettingsResponse;
import com.intermanage.backend.auth.entity.UserDashboardSettings;

@Mapper
public interface DashboardSettingsMapper {

    DashboardSettingsResponse toResponse(UserDashboardSettings settings);
}
