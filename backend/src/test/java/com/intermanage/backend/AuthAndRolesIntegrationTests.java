package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.intermanage.backend.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class AuthAndRolesIntegrationTests {

    private static final String OWNER = "wlasciciel";
    private static final String OWNER_PASSWORD = "haslo-wlasciciela";

    @Autowired
    MockMvc mvc;

    @Autowired
    RecordingAccountMailer mailer;

    @Test
    void meRequiresLogin() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void invitedUserActivatesAccountAndMustSetPasswordFirst() throws Exception {
        Cookie owner = login(OWNER, OWNER_PASSWORD);
        long roleId = createRole(owner, "{\"SALES\":\"VIEW\"}");
        String username = inviteUser(owner, roleId);

        // Przed aktywacją konto nie ma hasła - nie da się zalogować.
        mvc.perform(loginRequest(username, "")).andExpect(status().isBadRequest());
        mvc.perform(loginRequest(username, "cokolwiek-123")).andExpect(status().isUnauthorized());

        String token = mailer.lastTokenFor(emailOf(username));
        MvcResult activated = mvc.perform(activateRequest(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true))
                .andReturn();
        Cookie invited = tokenCookie(activated);

        // Link jest jednorazowy.
        mvc.perform(activateRequest(token)).andExpect(status().isBadRequest());

        // Dopóki hasło nie jest ustawione, reszta API jest zablokowana.
        mvc.perform(get("/api/auth/me").cookie(invited)).andExpect(status().isOk());
        mvc.perform(get("/api/permission-categories").cookie(invited)).andExpect(status().isForbidden());
        mvc.perform(put("/api/auth/password").cookie(invited).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"x\",\"newPassword\":\"nowe-haslo-123\"}"))
                .andExpect(status().isForbidden());

        MvcResult setUp = mvc.perform(setUpPasswordRequest("haslo-pracownika").cookie(invited))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(false))
                .andExpect(jsonPath("$.permissions.SALES").value("VIEW"))
                .andReturn();
        mvc.perform(get("/api/permission-categories").cookie(tokenCookie(setUp))).andExpect(status().isOk());
        mvc.perform(setUpPasswordRequest("inne-haslo-123").cookie(tokenCookie(setUp))).andExpect(status().isConflict());
        login(username, "haslo-pracownika");
    }

    @Test
    void resendingActivationLinkInvalidatesThePreviousOne() throws Exception {
        Cookie owner = login(OWNER, OWNER_PASSWORD);
        long roleId = createRole(owner, "{}");
        String username = inviteUser(owner, roleId);
        String firstToken = mailer.lastTokenFor(emailOf(username));

        mvc.perform(post("/api/settings/users/" + userId(owner, username) + "/activation-link").cookie(owner).with(csrf()))
                .andExpect(status().isNoContent());

        mvc.perform(activateRequest(firstToken)).andExpect(status().isBadRequest());
        mvc.perform(activateRequest(mailer.lastTokenFor(emailOf(username)))).andExpect(status().isOk());
    }

    @Test
    void temporaryPasswordFromOwnerMustBeChanged() throws Exception {
        Cookie owner = login(OWNER, OWNER_PASSWORD);
        long roleId = createRole(owner, "{}");
        String username = createUser(owner, roleId);
        long userId = userId(owner, username);

        mvc.perform(put("/api/settings/users/" + userId).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Jan\",\"email\":\"" + emailOf(username) + "\",\"roleId\":"
                                + roleId + ",\"enabled\":true,\"newPassword\":\"tymczasowe-123\"}"))
                .andExpect(status().isOk());

        Cookie employee = login(username, "tymczasowe-123");
        mvc.perform(get("/api/auth/me").cookie(employee))
                .andExpect(jsonPath("$.passwordChangeRequired").value(true));
        mvc.perform(get("/api/permission-categories").cookie(employee)).andExpect(status().isForbidden());
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        Cookie owner = login(OWNER, OWNER_PASSWORD);
        long roleId = createRole(owner, "{}");
        String username = inviteUser(owner, roleId);

        mvc.perform(post("/api/settings/users").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"inny" + username + "\",\"displayName\":\"Jan\","
                                + "\"email\":\"" + emailOf(username).toUpperCase() + "\",\"roleId\":" + roleId + "}"))
                .andExpect(status().isConflict());
    }

    @Test
    void logoutClearsTokenCookie() throws Exception {
        Cookie owner = login(OWNER, OWNER_PASSWORD);

        MvcResult result = mvc.perform(post("/api/auth/logout").cookie(owner).with(csrf()))
                .andExpect(status().isNoContent())
                .andReturn();
        Cookie cleared = result.getResponse().getCookie(JwtService.COOKIE_NAME);
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
    }

    @Test
    void invalidTokenIsRejectedButDoesNotBlockLogin() throws Exception {
        Cookie forged = new Cookie(JwtService.COOKIE_NAME, "nie-jest-to-jwt");

        mvc.perform(get("/api/auth/me").cookie(forged)).andExpect(status().isUnauthorized());
        mvc.perform(loginRequest(OWNER, OWNER_PASSWORD).cookie(forged)).andExpect(status().isOk());
    }

    @Test
    void ownerLogsInAndHasAllPermissions() throws Exception {
        Cookie session = login(OWNER, OWNER_PASSWORD);

        mvc.perform(get("/api/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").value(true))
                .andExpect(jsonPath("$.permissions.SALES").value("EDIT"))
                .andExpect(jsonPath("$.permissions.IMPORTS").value("EDIT"));
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        mvc.perform(loginRequest(OWNER, "zle-haslo"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Nieprawidłowy login lub hasło"));
    }

    @Test
    void mutatingRequestWithoutCsrfIsRejected() throws Exception {
        Cookie session = login(OWNER, OWNER_PASSWORD);

        mvc.perform(post("/api/settings/roles").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bez CSRF\",\"permissions\":{}}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void dynamicRoleGrantsOnlyAssignedCategories() throws Exception {
        Cookie owner = login(OWNER, OWNER_PASSWORD);
        long roleId = createRole(owner, "{\"SALES\":\"VIEW\",\"INVENTORY\":\"EDIT\"}");
        String username = createUser(owner, roleId);

        Cookie employee = login(username, "haslo-pracownika");
        mvc.perform(get("/api/auth/me").cookie(employee))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").value(false))
                .andExpect(jsonPath("$.permissions.SALES").value("VIEW"))
                .andExpect(jsonPath("$.permissions.INVENTORY").value("EDIT"))
                .andExpect(jsonPath("$.permissions.STAFF").doesNotExist());

        mvc.perform(get("/api/settings/roles").cookie(employee)).andExpect(status().isForbidden());
    }

    @Test
    void roleChangeAppliesToExistingSession() throws Exception {
        Cookie owner = login(OWNER, OWNER_PASSWORD);
        long roleId = createRole(owner, "{\"SALES\":\"VIEW\"}");
        String username = createUser(owner, roleId);
        Cookie employee = login(username, "haslo-pracownika");

        mvc.perform(put("/api/settings/roles/" + roleId).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Zmieniona " + UUID.randomUUID() + "\",\"permissions\":{\"STAFF\":\"EDIT\"}}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/auth/me").cookie(employee))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.SALES").doesNotExist())
                .andExpect(jsonPath("$.permissions.STAFF").value("EDIT"));
    }

    @Test
    void disablingUserEndsTheirSession() throws Exception {
        Cookie owner = login(OWNER, OWNER_PASSWORD);
        long roleId = createRole(owner, "{}");
        String username = createUser(owner, roleId);
        long userId = userId(owner, username);
        Cookie employee = login(username, "haslo-pracownika");

        mvc.perform(put("/api/settings/users/" + userId).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Jan\",\"email\":\"" + emailOf(username) + "\",\"roleId\":"
                                + roleId + ",\"enabled\":false}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/auth/me").cookie(employee)).andExpect(status().isUnauthorized());
        mvc.perform(loginRequest(username, "haslo-pracownika"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordResetByOwnerEndsOldSessions() throws Exception {
        Cookie owner = login(OWNER, OWNER_PASSWORD);
        long roleId = createRole(owner, "{}");
        String username = createUser(owner, roleId);
        long userId = userId(owner, username);
        Cookie employee = login(username, "haslo-pracownika");

        mvc.perform(put("/api/settings/users/" + userId).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Jan\",\"email\":\"" + emailOf(username) + "\",\"roleId\":"
                                + roleId + ",\"enabled\":true,\"newPassword\":\"nowe-haslo-123\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/auth/me").cookie(employee)).andExpect(status().isUnauthorized());
        login(username, "nowe-haslo-123");
    }

    @Test
    void ownChangePasswordKeepsCurrentDevice() throws Exception {
        Cookie owner = login(OWNER, OWNER_PASSWORD);
        long roleId = createRole(owner, "{}");
        String username = createUser(owner, roleId);
        Cookie employee = login(username, "haslo-pracownika");

        mvc.perform(put("/api/auth/password").cookie(employee).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"zle\",\"newPassword\":\"nowe-haslo-123\"}"))
                .andExpect(status().isBadRequest());

        MvcResult changed = mvc.perform(put("/api/auth/password").cookie(employee).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"haslo-pracownika\",\"newPassword\":\"nowe-haslo-123\"}"))
                .andExpect(status().isNoContent())
                .andReturn();

        // Bieżące urządzenie dostaje nowy token, stary (np. z innego urządzenia) przestaje działać.
        mvc.perform(get("/api/auth/me").cookie(tokenCookie(changed))).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").cookie(employee)).andExpect(status().isUnauthorized());
        login(username, "nowe-haslo-123");
    }

    @Test
    void ownerRoleIsProtected() throws Exception {
        Cookie owner = login(OWNER, OWNER_PASSWORD);
        MvcResult me = mvc.perform(get("/api/auth/me").cookie(owner)).andReturn();
        long ownerId = ((Number) JsonPath.read(me.getResponse().getContentAsString(), "$.id")).longValue();
        long ownerRoleId = userRoleId(owner, OWNER);
        long otherRoleId = createRole(owner, "{}");

        mvc.perform(delete("/api/settings/roles/" + ownerRoleId).cookie(owner).with(csrf()))
                .andExpect(status().isConflict());

        // Jedyny właściciel nie może odebrać sobie roli ani się zablokować.
        mvc.perform(put("/api/settings/users/" + ownerId).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Właściciel\",\"roleId\":" + otherRoleId + ",\"enabled\":true}"))
                .andExpect(status().isConflict());
    }

    @Test
    void roleInUseCannotBeDeleted() throws Exception {
        Cookie owner = login(OWNER, OWNER_PASSWORD);
        long roleId = createRole(owner, "{}");
        createUser(owner, roleId);

        mvc.perform(delete("/api/settings/roles/" + roleId).cookie(owner).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void validationErrorsAreReturnedPerField() throws Exception {
        Cookie owner = login(OWNER, OWNER_PASSWORD);

        mvc.perform(post("/api/settings/users").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a\",\"displayName\":\"\",\"email\":\"to-nie-mail\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.username").exists())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.roleId").exists());
    }

    private Cookie login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(loginRequest(username, password))
                .andExpect(status().isOk())
                .andReturn();
        return tokenCookie(result);
    }

    private MockHttpServletRequestBuilder loginRequest(String username, String password) {
        return post("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }

    private static Cookie tokenCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(JwtService.COOKIE_NAME);
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        return cookie;
    }

    private long createRole(Cookie owner, String permissionsJson) throws Exception {
        MvcResult result = mvc.perform(post("/api/settings/roles").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rola " + UUID.randomUUID() + "\",\"permissions\":" + permissionsJson + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** Zaprasza pracownika (konto bez hasła, link aktywacyjny na {@link #emailOf}). */
    private String inviteUser(Cookie owner, long roleId) throws Exception {
        String username = "user" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/settings/users").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"Jan\","
                                + "\"email\":\"" + emailOf(username) + "\",\"roleId\":" + roleId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pendingActivation").value(true));
        return username;
    }

    /** Zaprasza pracownika, aktywuje konto linkiem i ustawia hasło {@code haslo-pracownika}. */
    private String createUser(Cookie owner, long roleId) throws Exception {
        String username = inviteUser(owner, roleId);
        MvcResult activated = mvc.perform(activateRequest(mailer.lastTokenFor(emailOf(username))))
                .andExpect(status().isOk())
                .andReturn();
        mvc.perform(setUpPasswordRequest("haslo-pracownika").cookie(tokenCookie(activated)))
                .andExpect(status().isOk());
        return username;
    }

    private static String emailOf(String username) {
        return username + "@sklep.test";
    }

    private MockHttpServletRequestBuilder activateRequest(String token) {
        return post("/api/auth/activate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}");
    }

    private MockHttpServletRequestBuilder setUpPasswordRequest(String password) {
        return post("/api/auth/password/setup").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"" + password + "\"}");
    }

    private long userId(Cookie owner, String username) throws Exception {
        String json = mvc.perform(get("/api/settings/users").cookie(owner)).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.<java.util.List<Object>>read(json, "$[?(@.username == '" + username + "')].id").get(0))
                .longValue();
    }

    private long userRoleId(Cookie owner, String username) throws Exception {
        String json = mvc.perform(get("/api/settings/users").cookie(owner)).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.<java.util.List<Object>>read(json, "$[?(@.username == '" + username + "')].roleId")
                .get(0)).longValue();
    }
}
