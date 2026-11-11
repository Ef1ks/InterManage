package com.intermanage.backend.staff.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.staff.dto.MyScheduleResponse;
import com.intermanage.backend.staff.entity.StaffScheduleVersionDayOff;
import com.intermanage.backend.staff.entity.StaffScheduleVersionEntry;

@Mapper
public interface MyScheduleMapper {

    @Mapping(target = "storeNumber", source = "version.schedule.store.number")
    @Mapping(target = "storeName", source = "version.schedule.store.name")
    @Mapping(target = "department", source = "version.schedule.department.name")
    MyScheduleResponse.Shift toShift(StaffScheduleVersionEntry entry);

    MyScheduleResponse.DayOff toDayOff(StaffScheduleVersionDayOff dayOff);
}
