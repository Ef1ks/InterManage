package com.intermanage.backend;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** Lista opakowań zwrotnych w Imporcie danych: prawdziwy wydruk sklepu 10023. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class DepositKindImportIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void importsDepositListOfItsStoreAndKeepsCodesForLabels() throws Exception {
        Cookie owner = login();
        byte[] pdf;
        try (InputStream in = getClass().getResourceAsStream("/pdf/deposit-kinds.pdf")) {
            // PDF-y są w .gitignore: bez pliku na dysku (CI) test się pomija.
            assumeTrue(in != null, "Brak test/resources/pdf/deposit-kinds.pdf");
            pdf = in.readAllBytes();
        }
        String storeId = store(owner, "10023");

        mvc.perform(multipart("/api/stores/{storeId}/imports/deposit-kinds", storeId)
                        .file(new MockMultipartFile("file", "opakowania.pdf", "application/pdf", pdf))
                        .cookie(owner).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalRows").value(10))
                .andExpect(jsonPath("$.reportGeneratedAt").value("2026-11-26T11:12:41"));

        mvc.perform(get("/api/stores/{storeId}/imports/deposit-kinds", storeId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kinds.length()").value(10))
                // Po kodzie: 1, 2, 3, 60, 70…
                .andExpect(jsonPath("$.kinds[4].code").value(70))
                .andExpect(jsonPath("$.kinds[4].name").value("KAUCJA PUSZKA METALOWA"))
                .andExpect(jsonPath("$.kinds[4].value").value(0.5))
                .andExpect(jsonPath("$.imports[0].fileName").value("opakowania.pdf"));

        mvc.perform(get("/api/stores/{storeId}/imports/history", storeId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].kind").value("depositKinds"));

        // Lista innego sklepu nie trafia do wybranego.
        String other = store(owner, String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 999_999)));
        mvc.perform(multipart("/api/stores/{storeId}/imports/deposit-kinds", other)
                        .file(new MockMultipartFile("file", "opakowania.pdf", "application/pdf", pdf))
                        .cookie(owner).with(csrf()))
                .andExpect(status().isConflict());
    }

    /** Sklep o numerze; numer jest unikalny, a baza testów wspólna, więc istniejący bierzemy. */
    private String store(Cookie owner, String number) throws Exception {
        List<String> found = jdbc.queryForList("select cast(id as text) from stores where number = ?", String.class,
                number);
        if (!found.isEmpty()) {
            return found.getFirst();
        }
        String json = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep kaucji " + number
                                + "\",\"active\":true}"))
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
