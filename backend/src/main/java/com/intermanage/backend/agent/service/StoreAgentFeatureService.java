package com.intermanage.backend.agent.service;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.agent.dto.AgentFeatureResponse;
import com.intermanage.backend.agent.entity.AgentFeature;
import com.intermanage.backend.agent.entity.StoreAgentFeature;
import com.intermanage.backend.agent.repository.StoreAgentFeatureRepository;
import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.cadtop.entity.CadPrioritySettings;
import com.intermanage.backend.cadtop.repository.CadPrioritySettingsRepository;
import com.intermanage.backend.common.component.LiveEvents;

/**
 * Co agent robi w sklepie: każdą funkcję sklep włącza i wyłącza sam. Bez zapisanego wyboru funkcja jest włączona, poza
 * ITM8 z cadencier, które ma własne ustawienie (to samo co na swojej stronie).
 */
@Service
public class StoreAgentFeatureService {

    /** Sklep wyłączył funkcję: pliki czekające na jej makro zostają do wczytania ręcznie. */
    public record SwitchedOff(UUID storeId, AgentFeature feature) {
    }

    private final StoreAgentFeatureRepository features;
    private final CadPrioritySettingsRepository cadPriorities;
    private final AppUserRepository users;
    private final LiveEvents live;
    private final ApplicationEventPublisher events;

    public StoreAgentFeatureService(StoreAgentFeatureRepository features, CadPrioritySettingsRepository cadPriorities,
            AppUserRepository users, LiveEvents live, ApplicationEventPublisher events) {
        this.features = features;
        this.cadPriorities = cadPriorities;
        this.users = users;
        this.live = live;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public boolean isOn(UUID storeId, AgentFeature feature) {
        return !off(storeId).contains(feature);
    }

    /** Wyłączone funkcje sklepu. */
    @Transactional(readOnly = true)
    public Set<AgentFeature> off(UUID storeId) {
        Set<AgentFeature> off = EnumSet.noneOf(AgentFeature.class);
        features.findByStoreId(storeId).stream().filter(item -> !item.isEnabled())
                .forEach(item -> off.add(item.getFeature()));
        if (!cadPriorities.findById(storeId).map(CadPrioritySettings::isAutomatic).orElse(false)) {
            off.add(AgentFeature.CAD_PRIORITY_MACRO);
        }
        return off;
    }

    @Transactional(readOnly = true)
    public List<AgentFeatureResponse> list(UUID storeId) {
        Set<AgentFeature> off = off(storeId);
        return Arrays.stream(AgentFeature.values())
                .map(feature -> new AgentFeatureResponse(feature, !off.contains(feature))).toList();
    }

    @Transactional
    public List<AgentFeatureResponse> set(UUID storeId, AgentFeature feature, boolean enabled, UUID userId) {
        AppUser user = users.getReferenceById(userId);
        if (feature == AgentFeature.CAD_PRIORITY_MACRO) {
            CadPrioritySettings value = cadPriorities.findById(storeId)
                    .orElseGet(() -> new CadPrioritySettings(storeId));
            value.update(enabled, user);
            cadPriorities.saveAndFlush(value);
        } else {
            StoreAgentFeature value = features.findByStoreIdAndFeature(storeId, feature)
                    .orElseGet(() -> new StoreAgentFeature(storeId, feature));
            value.update(enabled, user);
            features.saveAndFlush(value);
        }
        if (!enabled) {
            events.publishEvent(new SwitchedOff(storeId, feature));
        }
        live.publish(storeId, "agent", Map.of("action", "settings"));
        return list(storeId);
    }
}
