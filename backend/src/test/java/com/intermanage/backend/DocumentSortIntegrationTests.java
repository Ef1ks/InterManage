package com.intermanage.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class DocumentSortIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    /** Numer dokumentu to tekst, ale sortuje się jak liczba: 10000 jest większy niż 9999. */
    @Test
    void documentNumbersSortLikeNumbers() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        for (String number : new String[] {"999900", "1000000", "1000100", "998800"}) {
            insertDocument(storeId, number);
        }
        String list = "/api/stores/" + storeId + "/documents/pz";
        mvc.perform(get(list).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[*].number")
                        .value(org.hamcrest.Matchers.contains("1000100", "1000000", "999900", "998800")));
        mvc.perform(get(list).param("sort", "number").param("direction", "asc").cookie(owner))
                .andExpect(jsonPath("$.documents[*].number")
                        .value(org.hamcrest.Matchers.contains("998800", "999900", "1000000", "1000100")));
    }

    private void insertDocument(String storeId, String number) {
        String importId = jdbc.queryForObject("""
                insert into document_imports (store_id, file_name, total_lines, saved_lines, created_count,
                    updated_count, skipped_lines)
                values (?::uuid, 'Zeszyt2.xlsx', 1, 1, 1, 0, 0) returning id""", String.class, storeId);
        jdbc.update("""
                insert into documents (store_id, type, number, document_date, line_count, purchase_value,
                    sale_value_gross, first_import_id, last_import_id)
                values (?::uuid, 'PZ', ?, ?, 0, 0, 0, ?::uuid, ?::uuid)""",
                storeId, number, LocalDateTime.of(2026, 10, 20, 8, 0), importId, importId);
    }

    private String createStore(Cookie owner) throws Exception {
        String number = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999_999L));
        String json = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep " + number + "\",\"active\":true}"))
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
