package com.intermanage.backend.sales.mapper;

import java.util.List;

import org.mapstruct.Mapper;

import com.intermanage.backend.sales.dto.LossControlGroupResponse;
import com.intermanage.backend.sales.entity.LossControlGroup;

@Mapper
public interface LossControlGroupMapper {

    LossControlGroupResponse toResponse(LossControlGroup group, List<String> itm8s);
}
