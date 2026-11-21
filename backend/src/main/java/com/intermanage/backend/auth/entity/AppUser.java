package com.intermanage.backend.auth.entity;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class AppUser {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    /** Zawsze zapisywany małymi literami. */
    @Column(nullable = false, length = 50, updatable = false)
    private String username;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    /** Zawsze zapisywany małymi literami. Właściciel z bootstrapu może go nie mieć. */
    @Column(length = 255)
    private String email;

    /** Brak = konto czeka na aktywację linkiem z maila. */
    @Column(name = "password_hash")
    private String passwordHash;

    /** Po zalogowaniu użytkownik musi ustawić własne hasło (nowe konto, hasło tymczasowe od właściciela). */
    @Column(name = "password_change_required", nullable = false)
    private boolean passwordChangeRequired;

    /** Właściciel pozwolił temu kontu zlecać zadania (właściciel zleca zawsze). */
    @Column(name = "can_assign_tasks", nullable = false)
    private boolean canAssignTasks;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Sklepy, do których użytkownik ma dostęp. Właściciel widzi wszystkie sklepy niezależnie od tej listy. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_stores", joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "store_id"))
    private Set<Store> stores = new HashSet<>();

    /** Ranga konta w każdym sklepie (hierarchia sklepu). Właściciel aplikacji ich nie potrzebuje. */
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    private Set<UserStoreRole> storeRoles = new HashSet<>();

    /**
     * Zwiększana przy zmianie hasła. Tokeny wydane ze starszą wersją są odrzucane
     * (patrz {@link com.intermanage.backend.auth.security.JwtAuthenticationFilter}).
     */
    @Column(name = "session_version", nullable = false)
    private int sessionVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private UUID deletedBy;

    /** Czy konto było odblokowane przed usunięciem: przywrócenie oddaje je w tym stanie. */
    @Column(name = "enabled_before_delete")
    private Boolean enabledBeforeDelete;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AppUser() {
    }

    /** Konto zaproszonego pracownika: bez hasła, aktywowane linkiem z maila. */
    public static AppUser invited(String username, String displayName, String email, Role role) {
        AppUser user = new AppUser(username, displayName, null, role);
        user.email = normalizeEmail(email);
        user.passwordChangeRequired = true;
        return user;
    }

    public AppUser(String username, String displayName, String passwordHash, Role role) {
        this.username = normalizeUsername(username);
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public static String normalizeUsername(String username) {
        return username.trim().toLowerCase();
    }

    public static String normalizeEmail(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase();
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Własne hasło użytkownika. Unieważnia tokeny z innych urządzeń. */
    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
        this.passwordChangeRequired = false;
        this.sessionVersion++;
    }

    /** Hasło tymczasowe ustawione przez właściciela: wylogowuje użytkownika i wymusza zmianę po zalogowaniu. */
    public void resetPassword(String temporaryPasswordHash) {
        changePassword(temporaryPasswordHash);
        this.passwordChangeRequired = true;
    }

    public void requirePasswordChange() {
        this.passwordChangeRequired = true;
    }

    /** Usunięcie przez właściciela: znacznik, blokada i wylogowanie ze wszystkich urządzeń. */
    public void markDeleted(UUID by) {
        this.enabledBeforeDelete = enabled;
        this.enabled = false;
        this.deletedAt = Instant.now();
        this.deletedBy = by;
        this.sessionVersion++;
    }

    public void restoreDeleted() {
        this.enabled = Boolean.TRUE.equals(enabledBeforeDelete);
        this.enabledBeforeDelete = null;
        this.deletedAt = null;
        this.deletedBy = null;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public boolean isPendingActivation() {
        return passwordHash == null;
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = normalizeEmail(email);
    }

    public boolean isPasswordChangeRequired() {
        return passwordChangeRequired;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public Set<Store> getStores() {
        return stores;
    }

    public void setStores(Set<Store> stores) {
        this.stores.clear();
        this.stores.addAll(stores);
    }

    public Set<UserStoreRole> getStoreRoles() {
        return storeRoles;
    }

    /** Właściciel aplikacji: wszystkie sklepy i ustawienia firmy. */
    public boolean isOwner() {
        return role != null && role.isOwner();
    }

    /** Ranga w sklepie, {@code null} = brak (właściciel albo konto bez przypisanej rangi). */
    public StoreRole rankIn(UUID storeId) {
        if (storeId == null) {
            return null;
        }
        return storeRoles.stream().filter(assignment -> assignment.getStore().getId().equals(storeId))
                .map(UserStoreRole::getRole).filter(rank -> rank.getDeletedAt() == null).findFirst().orElse(null);
    }

    /** Nazwa rangi w sklepie do list: właściciel „Właściciel”, bez rangi pusta. */
    public String rankNameIn(UUID storeId) {
        if (isOwner()) {
            return role.getName();
        }
        StoreRole rank = rankIn(storeId);
        return rank == null ? null : rank.getName();
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isCanAssignTasks() {
        return canAssignTasks;
    }

    public void setCanAssignTasks(boolean canAssignTasks) {
        this.canAssignTasks = canAssignTasks;
    }

    /** Czy konto zleca zadania: właściciel zawsze, inni z włączonym zlecaniem. */
    public boolean mayAssignTasks() {
        return canAssignTasks || isOwner();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getSessionVersion() {
        return sessionVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
