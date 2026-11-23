package com.intermanage.backend.agent.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.agent.dto.AgentReportFileResponse;
import com.intermanage.backend.agent.dto.StoreAgentResponse;
import com.intermanage.backend.agent.entity.AgentReportFile;
import com.intermanage.backend.agent.entity.StoreAgent;

@Mapper
public interface StoreAgentMapper {

    @Mapping(target = "id", source = "agent.id")
    @Mapping(target = "name", source = "agent.name")
    @Mapping(target = "createdAt", source = "agent.createdAt")
    @Mapping(target = "createdBy", source = "agent.createdBy.displayName")
    @Mapping(target = "lastSeenAt", source = "agent.lastSeenAt")
    @Mapping(target = "lastVersion", source = "agent.lastVersion")
    @Mapping(target = "machineName", source = "agent.machineName")
    @Mapping(target = "machineUser", source = "agent.machineUser")
    @Mapping(target = "deletedAt", source = "agent.deletedAt")
    @Mapping(target = "online", source = "online")
    @Mapping(target = "preferred", source = "agent.preferred")
    @Mapping(target = "leader", source = "leader")
    @Mapping(target = "key", source = "key")
    StoreAgentResponse toResponse(StoreAgent agent, boolean online, boolean leader, String key);

    AgentReportFileResponse toResponse(AgentReportFile file);
}
