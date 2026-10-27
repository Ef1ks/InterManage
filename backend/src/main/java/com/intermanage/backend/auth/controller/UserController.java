package com.intermanage.backend.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.dto.CreateUserRequest;
import com.intermanage.backend.dto.UpdateUserRequest;
import com.intermanage.backend.dto.UserResponse;
import com.intermanage.backend.service.UserService;

import jakarta.validation.Valid;

/** Tylko dla właściciela - reguła dla /api/settings/** w SecurityConfig. */
@RestController
@RequestMapping("/api/settings/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<UserResponse> list() {
        return userService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return userService.create(request);
    }

    @PostMapping("/{id}/activation-link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resendActivationLink(@PathVariable UUID id) {
        userService.resendActivationLink(id);
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(id, request);
    }
}
