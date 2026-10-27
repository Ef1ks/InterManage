package com.intermanage.backend.controller;

import java.util.Arrays;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.dto.PermissionCategoryResponse;
import com.intermanage.backend.entity.PermissionCategory;
import com.intermanage.backend.mapper.PermissionCategoryMapper;

@RestController
@RequestMapping("/api/permission-categories")
public class PermissionCategoryController {

    private final PermissionCategoryMapper permissionCategoryMapper;

    public PermissionCategoryController(PermissionCategoryMapper permissionCategoryMapper) {
        this.permissionCategoryMapper = permissionCategoryMapper;
    }

    @GetMapping
    public List<PermissionCategoryResponse> list() {
        return Arrays.stream(PermissionCategory.values())
                .map(permissionCategoryMapper::toResponse)
                .toList();
    }
}
