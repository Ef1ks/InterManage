package com.intermanage.backend.staff.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.staff.dto.StaffRecordResponse;
import com.intermanage.backend.staff.entity.StaffContract;
import com.intermanage.backend.staff.entity.StaffContractChange;
import com.intermanage.backend.staff.entity.StaffDisability;
import com.intermanage.backend.staff.entity.StaffEmployee;
import com.intermanage.backend.staff.entity.StaffLeave;
import com.intermanage.backend.staff.entity.StaffLeaveOpening;
import com.intermanage.backend.staff.entity.StaffProfile;
import com.intermanage.backend.staff.entity.StaffWorkPeriod;

@Mapper
public interface StaffRecordMapper {

    @Mapping(target = "employeeId", source = "id")
    @Mapping(target = "displayName", source = "user.displayName")
    @Mapping(target = "username", source = "user.username")
    @Mapping(target = "email", source = "user.email")
    @Mapping(target = "storeNumber", source = "store.number")
    @Mapping(target = "storeName", source = "store.name")
    @Mapping(target = "departmentName", source = "position.department.name")
    @Mapping(target = "positionName", source = "position.name")
    @Mapping(target = "roleName", source = "user.role.name")
    StaffRecordResponse.Person toPerson(StaffEmployee employee);

    StaffRecordResponse.Profile toProfile(StaffProfile profile);

    @Mapping(target = "id", source = "contract.id")
    @Mapping(target = "kind", source = "contract.kind")
    @Mapping(target = "employmentType", source = "contract.employmentType")
    @Mapping(target = "startDate", source = "contract.startDate")
    @Mapping(target = "endDate", source = "contract.endDate")
    @Mapping(target = "fteNumerator", source = "contract.fteNumerator")
    @Mapping(target = "fteDenominator", source = "contract.fteDenominator")
    @Mapping(target = "fixedTermExempt", source = "contract.fixedTermExempt")
    @Mapping(target = "changes", source = "changes")
    StaffRecordResponse.Contract toContract(StaffContract contract, List<StaffContractChange> changes);

    StaffRecordResponse.ContractChange toContractChange(StaffContractChange change);

    StaffRecordResponse.WorkPeriod toWorkPeriod(StaffWorkPeriod period);

    StaffRecordResponse.Disability toDisability(StaffDisability disability);

    StaffRecordResponse.Leave toLeave(StaffLeave leave);

    StaffRecordResponse.Opening toOpening(StaffLeaveOpening opening);
}
