package com.intermanage.backend.permission;

import java.util.Arrays;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/permission-categories")
public class PermissionCategoryController {

    public record PermissionCategoryResponse(PermissionCategory code, String label, String description) {
    }

    @GetMapping
    public List<PermissionCategoryResponse> list() {
        return Arrays.stream(PermissionCategory.values())
                .map(c -> new PermissionCategoryResponse(c, c.label(), c.description()))
                .toList();
    }
}
