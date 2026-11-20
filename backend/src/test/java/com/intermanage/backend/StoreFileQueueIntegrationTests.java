package com.intermanage.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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
class StoreFileQueueIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Test
    void filesWaitInOrderUntilSavedInStore() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String otherStoreId = createStore(owner);

        String first = JsonPath.read(add(owner, storeId, "deli-order-proto", "PROTO1_20261117_101500_10023_jan.Traite0",
                "5900000000001000001000\r\n").andExpect(status().isOk())
                .andExpect(jsonPath("$.folderLabel").value("Protokół"))
                .andExpect(jsonPath("$.groupKey").doesNotExist())
                .andReturn().getResponse().getContentAsString(), "$.id");
        // Plik faktury niesie dokument, do którego należy: okno kolejki łączy jego pliki w jeden wiersz.
        String second = JsonPath.read(mvc.perform(multipart("/api/stores/" + storeId + "/file-queue").cookie(owner)
                        .with(csrf())
                        .file(new MockMultipartFile("file", "faktura.xml", MediaType.APPLICATION_OCTET_STREAM_VALUE,
                                "<DOKUMENT/>".getBytes(StandardCharsets.UTF_8)))
                        .param("folderKey", "data-entry-xml")
                        .param("groupKey", "ksef:123")
                        .param("groupLabel", "FV 1/2026 · GOBARTO")
                        .param("fileName", "faktura.xml"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupKey").value("ksef:123"))
                .andExpect(jsonPath("$.groupLabel").value("FV 1/2026 · GOBARTO"))
                .andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(get("/api/stores/" + storeId + "/file-queue").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(first))
                .andExpect(jsonPath("$[1].id").value(second));
        mvc.perform(get("/api/stores/" + otherStoreId + "/file-queue").cookie(owner))
                .andExpect(jsonPath("$.length()").value(0));
        // Plik innego sklepu nie jest widoczny pod tym sklepem.
        mvc.perform(get("/api/stores/" + otherStoreId + "/file-queue/" + first + "/file").cookie(owner))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/stores/" + storeId + "/file-queue/" + first + "/file").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(content().bytes("5900000000001000001000\r\n".getBytes(StandardCharsets.UTF_8)));

        // Zapis tylko po przydziale (agent sklepu i przeglądarka nie zapiszą pliku dwa razy).
        mvc.perform(post("/api/stores/" + storeId + "/file-queue/" + first + "/saved").cookie(owner).with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/stores/" + storeId + "/file-queue/" + first + "/claim").cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CLAIMED"));
        mvc.perform(post("/api/stores/" + storeId + "/file-queue/" + first + "/saved").cookie(owner).with(csrf()))
                .andExpect(status().isNoContent());
        // Usunięcie to znacznik z przywróceniem; zapisanego pliku nie da się już usunąć.
        mvc.perform(delete("/api/stores/" + storeId + "/file-queue/" + second).cookie(owner).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/stores/" + storeId + "/file-queue/count").cookie(owner))
                .andExpect(jsonPath("$.count").value(0));
        mvc.perform(post("/api/stores/" + storeId + "/file-queue/" + second + "/restore").cookie(owner).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/stores/" + storeId + "/file-queue").cookie(owner))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(second));
        mvc.perform(delete("/api/stores/" + storeId + "/file-queue/" + first).cookie(owner).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsBadNames() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        add(owner, storeId, "zły klucz", "a.txt", "x").andExpect(status().isBadRequest());
        add(owner, storeId, "data-entry-xml", "..\\a.xml", "x").andExpect(status().isBadRequest());
    }

    private ResultActions add(Cookie cookie, String storeId, String key, String fileName, String body)
            throws Exception {
        return mvc.perform(multipart("/api/stores/" + storeId + "/file-queue").cookie(cookie).with(csrf())
                .file(new MockMultipartFile("file", fileName, MediaType.APPLICATION_OCTET_STREAM_VALUE,
                        body.getBytes(StandardCharsets.UTF_8)))
                .param("folderKey", key)
                .param("folderLabel", key.equals("deli-order-proto") ? "Protokół" : "Plik XML faktury")
                .param("fileName", fileName));
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
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep kolejki\",\"active\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }
}
