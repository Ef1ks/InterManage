package com.intermanage.backend.staff.mapper;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.staff.dto.AccountOption;
import com.intermanage.backend.staff.dto.DepartmentResponse;
import com.intermanage.backend.staff.dto.EmployeeResponse;
import com.intermanage.backend.staff.dto.PositionResponse;
import com.intermanage.backend.staff.dto.ShiftResponse;
import com.intermanage.backend.staff.entity.StaffDepartment;
import com.intermanage.backend.staff.entity.StaffEmployee;
import com.intermanage.backend.staff.entity.StaffPosition;
import com.intermanage.backend.staff.entity.StaffShift;

@Mapper
public interface StaffMapper {

    DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    ShiftResponse toResponse(StaffShift shift);

    List<ShiftResponse> toShiftResponses(List<StaffShift> shifts);

    @Mapping(target = "id", source = "department.id")
    @Mapping(target = "name", source = "department.name")
    @Mapping(target = "positions", source = "positions")
    DepartmentResponse toResponse(StaffDepartment department, List<PositionResponse> positions);

    @Mapping(target = "id", source = "position.id")
    @Mapping(target = "departmentId", source = "position.department.id")
    @Mapping(target = "name", source = "position.name")
    @Mapping(target = "roleId", source = "position.role.id")
    @Mapping(target = "roleName", source = "position.role.name")
    @Mapping(target = "shifts", source = "shifts")
    @Mapping(target = "employeeCount", source = "employeeCount")
    PositionResponse toResponse(StaffPosition position, List<StaffShift> shifts, long employeeCount);

    @Mapping(target = "id", source = "employee.id")
    @Mapping(target = "userId", source = "employee.user.id")
    @Mapping(target = "displayName", expression = "java(employee.getDisplayName())")
    @Mapping(target = "hasAccount", expression = "java(employee.hasAccount())")
    @Mapping(target = "username", source = "employee.user.username")
    @Mapping(target = "email", source = "employee.user.email")
    @Mapping(target = "enabled", source = "employee.user.enabled")
    @Mapping(target = "pendingActivation", source = "employee.user.pendingActivation")
    @Mapping(target = "ownerAccount", source = "employee.user.role.owner")
    @Mapping(target = "roleName", source = "employee.user.role.name")
    @Mapping(target = "storeId", source = "employee.store.id")
    @Mapping(target = "storeNumber", source = "employee.store.number")
    @Mapping(target = "storeName", source = "employee.store.name")
    @Mapping(target = "departmentId", source = "employee.position.department.id")
    @Mapping(target = "departmentName", source = "employee.position.department.name")
    @Mapping(target = "positionId", source = "employee.position.id")
    @Mapping(target = "positionName", source = "employee.position.name")
    @Mapping(target = "shifts", source = "shifts")
    @Mapping(target = "createdAt", source = "employee.createdAt")
    @Mapping(target = "endedAt", source = "employee.endedAt")
    @Mapping(target = "extraPositionIds", source = "extraPositionIds")
    EmployeeResponse toResponse(StaffEmployee employee, List<StaffShift> shifts, List<UUID> extraPositionIds);

    @Mapping(target = "roleName", source = "role.name")
    @Mapping(target = "owner", source = "role.owner")
    AccountOption toAccountOption(AppUser user);

    default String time(LocalTime time) {
        return time == null ? null : time.format(TIME);
    }
}
