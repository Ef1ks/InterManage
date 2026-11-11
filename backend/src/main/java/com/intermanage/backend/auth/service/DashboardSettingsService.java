package com.intermanage.backend.auth.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.dto.DashboardSettingsResponse;
import com.intermanage.backend.auth.entity.DashboardChartMetric;
import com.intermanage.backend.auth.entity.UserDashboardSettings;
import com.intermanage.backend.auth.mapper.DashboardSettingsMapper;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.auth.repository.UserDashboardSettingsRepository;

/** Ustawienia pulpitu zalogowanego konta; bez zapisu obowiązują domyślne (wykres sprzedaży). */
@Service
public class DashboardSettingsService {

    private final UserDashboardSettingsRepository settings;
    private final AppUserRepository users;
    private final DashboardSettingsMapper mapper;

    public DashboardSettingsService(UserDashboardSettingsRepository settings, AppUserRepository users,
            DashboardSettingsMapper mapper) {
        this.settings = settings;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public DashboardSettingsResponse get(UUID userId) {
        return settings.findOfUser(userId)
                .map(mapper::toResponse)
                .orElseGet(() -> new DashboardSettingsResponse(DashboardChartMetric.SALES));
    }

    @Transactional
    public DashboardSettingsResponse save(UUID userId, DashboardSettingsResponse request) {
        UserDashboardSettings current = settings.findOfUser(userId)
                .orElseGet(() -> settings.save(new UserDashboardSettings(users.getReferenceById(userId))));
        current.setSalesChartMetric(request.salesChartMetric());
        return mapper.toResponse(current);
    }
}
