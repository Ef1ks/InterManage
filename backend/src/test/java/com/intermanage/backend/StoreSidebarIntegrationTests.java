package com.intermanage.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class StoreSidebarIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Test
    void savesOrderPerStoreAndReturnsItWithCurrentUser() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String otherStoreId = createStore(owner);

        mvc.perform(get("/api/auth/me").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sidebarOrders['" + storeId + "']").doesNotExist());

        // Powtórzony klucz zostaje raz, a ponowny zapis zastępuje kolejność.
        save(owner, storeId, "{\"groups\":[\"sales\",\"documents\",\"sales\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(2));
        save(owner, storeId, "{\"groups\":[\"purchases\",\"documents\"]}").andExpect(status().isOk());

        mvc.perform(get("/api/auth/me").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sidebarOrders['" + storeId + "'][0]").value("purchases"))
                .andExpect(jsonPath("$.sidebarOrders['" + storeId + "'][1]").value("documents"))
                .andExpect(jsonPath("$.sidebarOrders['" + otherStoreId + "']").doesNotExist());
    }

    @Test
    void rejectsInvalidGroupKey() throws Exception {
        Cookie owner = login();
        save(owner, createStore(owner), "{\"groups\":[\"Nie Klucz\"]}").andExpect(status().isBadRequest());
    }

    private ResultActions save(Cookie cookie, String storeId, String body) throws Exception {
        return mvc.perform(put("/api/stores/" + storeId + "/sidebar-order").cookie(cookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }

    private String createStore(Cookie cookie) throws Exception {
        String number = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        String body = mvc.perform(post("/api/settings/stores").cookie(cookie).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep menu\",\"active\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }
}
