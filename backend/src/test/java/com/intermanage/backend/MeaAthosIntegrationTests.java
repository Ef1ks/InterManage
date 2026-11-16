package com.intermanage.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** Odczyt dziennika ATHOS (Histo.Log) z folderu w przeglądarce: wysyłki MEA w bazie bez powtórzeń. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class MeaAthosIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Test
    void importsSendsOnceAndRejectsOtherFiles() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String url = "/api/stores/" + storeId + "/mea/athos";

        mvc.perform(get(url).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(false));

        List<String> lines = new ArrayList<>();
        lines.add(AthosHistoryParserTests.header("14/11/2026¦08:15:30"));
        lines.addAll(AthosHistoryParserTests.entry("81534", "  43", "   27"));
        lines.addAll(AthosHistoryParserTests.entry("81419", " 120", "  310"));
        byte[] first = AthosHistoryParserTests.file(lines);

        mvc.perform(multipart(url).file(new MockMultipartFile("file", "Histo.Log", "text/plain", first))
                        .param("modified", "1794650000000").cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true))
                .andExpect(jsonPath("$.foundCount").value(2))
                .andExpect(jsonPath("$.addedCount").value(2))
                .andExpect(jsonPath("$.sendCount").value(2))
                .andExpect(jsonPath("$.lastSentAt").value("2026-11-14T08:15:30"));

        // Dziennik rośnie: ponowny odczyt dopisuje tylko nową wysyłkę.
        lines.add(AthosHistoryParserTests.header("15/11/2026¦09:00:05"));
        lines.addAll(AthosHistoryParserTests.entry("81534", "  44", "   28"));
        mvc.perform(multipart(url)
                        .file(new MockMultipartFile("file", "Histo.Log", "text/plain",
                                AthosHistoryParserTests.file(lines)))
                        .cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.foundCount").value(3))
                .andExpect(jsonPath("$.addedCount").value(1))
                .andExpect(jsonPath("$.sendCount").value(3))
                .andExpect(jsonPath("$.lastSentAt").value("2026-11-15T09:00:05"));

        mvc.perform(multipart(url)
                        .file(new MockMultipartFile("file", "inny.txt", "text/plain", "nic tu nie ma".getBytes()))
                        .cookie(owner).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    private String createStore(Cookie owner) throws Exception {
        String number = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        String json = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep MEA\",\"active\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }
}
