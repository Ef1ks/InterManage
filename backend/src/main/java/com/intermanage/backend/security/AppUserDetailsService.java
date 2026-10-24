package com.intermanage.backend.security;

import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.user.AppUser;
import com.intermanage.backend.user.AppUserRepository;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public AppUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public AppUserPrincipal loadUserByUsername(String username) {
        // Konto czekające na aktywację nie ma hasła - logowanie hasłem nie jest możliwe.
        return users.findWithRoleByUsername(AppUser.normalizeUsername(username))
                .filter(user -> !user.isPendingActivation())
                .map(AppUserPrincipal::from)
                .orElseThrow(() -> new UsernameNotFoundException("Nie znaleziono użytkownika"));
    }
}
