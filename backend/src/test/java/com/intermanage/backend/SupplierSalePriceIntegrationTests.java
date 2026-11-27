package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** Stałe ceny sprzedaży dostawców: ustawienie, zmiana i usunięcie (znaczniki), walidacja i uprawnienia. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.ksef.sync-enabled=false"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class SupplierSalePriceIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    private Cookie owner;
    private String storeId;

    @BeforeEach
    void setUp() throws Exception {
        owner = login();
        storeId = createStore();
    }

    @Test
    void setsChangesAndRemovesPriceWithMarkers() throws Exception {
        String bakery = createSupplier("Piekarnia Zbożowa");
        String path = "/api/stores/" + storeId + "/supplier-sale-prices";

        mvc.perform(put(path + "/" + bakery).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"salePrice\":3.99}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].supplierId").value(bakery))
                .andExpect(jsonPath("$[0].supplierName").value("Piekarnia Zbożowa"))
                .andExpect(jsonPath("$[0].salePrice").value(3.99));

        // Ta sama cena niczego nie dopisuje, inna daje znacznik poprzedniej.
        mvc.perform(put(path + "/" + bakery).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"salePrice\":3.99}"))
                .andExpect(status().isOk());
        mvc.perform(put(path + "/" + bakery).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"salePrice\":4.49}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].salePrice").value(4.49));
        assertThat(count(bakery, true)).isEqualTo(1);
        assertThat(count(bakery, false)).isEqualTo(1);

        mvc.perform(delete(path + "/" + bakery).cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        assertThat(count(bakery, true)).isEqualTo(2);
        mvc.perform(delete(path + "/" + bakery).cookie(owner).with(csrf()))
                .andExpect(status().isNotFound());

        mvc.perform(get(path).cookie(owner)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void rejectsInvalidPriceAndSupplierFromOtherStore() throws Exception {
        String supplier = createSupplier("Dostawca");
        String path = "/api/stores/" + storeId + "/supplier-sale-prices/";

        mvc.perform(put(path + supplier).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"salePrice\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(path + supplier).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"salePrice\":1.999}"))
                .andExpect(status().isBadRequest());

        String otherStore = createStore();
        mvc.perform(put("/api/stores/" + otherStore + "/supplier-sale-prices/" + supplier).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"salePrice\":5.00}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void readingNeedsDataEntryViewAndChangingNeedsEdit() throws Exception {
        String supplier = createSupplier("Dostawca");
        String path = "/api/stores/" + storeId + "/supplier-sale-prices";
        Cookie viewer = employee("{\"DATA_ENTRY\":\"VIEW\"}");
        Cookie sales = employee("{\"SALES\":\"EDIT\"}");

        mvc.perform(get(path).cookie(viewer)).andExpect(status().isOk());
        mvc.perform(get(path).cookie(sales)).andExpect(status().isForbidden());
        mvc.perform(put(path + "/" + supplier).cookie(viewer).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"salePrice\":3.99}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void supplierMarginHasDefaultAndNomenclatureExceptions() throws Exception {
        createNomenclature();
        String supplier = createSupplier("Hurtownia Marż");
        String path = "/api/stores/" + storeId + "/supplier-margins";

        mvc.perform(put(path + "/" + supplier).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"defaultPercent":30,"rules":[{"code":"01","marginPercent":25},
                                {"code":"0201","marginPercent":20}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].supplierId").value(supplier))
                .andExpect(jsonPath("$[0].defaultPercent").value(30))
                .andExpect(jsonPath("$[0].rules", hasSize(2)))
                .andExpect(jsonPath("$[0].rules[0].code").value("01"))
                .andExpect(jsonPath("$[0].rules[0].name").value("Pozycja 01"))
                .andExpect(jsonPath("$[0].rules[0].marginPercent").value(25))
                .andExpect(jsonPath("$[0].rules[1].code").value("0201"));
        assertThat(marginCount(supplier, false)).isEqualTo(3);

        // Zmieniony wyjątek i usunięta domyślna dostają znacznik, niezmieniony zostaje.
        mvc.perform(put(path + "/" + supplier).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"defaultPercent":null,"rules":[{"code":"01","marginPercent":25},
                                {"code":"0201","marginPercent":22.5}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].defaultPercent").doesNotExist())
                .andExpect(jsonPath("$[0].rules[1].marginPercent").value(22.5));
        assertThat(marginCount(supplier, false)).isEqualTo(2);
        assertThat(marginCount(supplier, true)).isEqualTo(2);

        // Kod spoza nomenklatury sklepu i ten sam kod dwa razy to 400.
        mvc.perform(put(path + "/" + supplier).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultPercent\":30,\"rules\":[{\"code\":\"99\",\"marginPercent\":25}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(path + "/" + supplier).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"defaultPercent":30,"rules":[{"code":"01","marginPercent":25},
                                {"code":"01","marginPercent":20}]}"""))
                .andExpect(status().isBadRequest());

        // Bez domyślnej i wyjątków marża dostawcy znika.
        mvc.perform(put(path + "/" + supplier).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"defaultPercent\":null,\"rules\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        Cookie suppliersViewer = employee("{\"SUPPLIERS\":\"VIEW\"}");
        mvc.perform(get(path).cookie(suppliersViewer)).andExpect(status().isOk());
        mvc.perform(put(path + "/" + supplier).cookie(suppliersViewer).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"defaultPercent\":30,\"rules\":[]}"))
                .andExpect(status().isForbidden());
        Cookie suppliersEditor = employee("{\"SUPPLIERS\":\"EDIT\"}");
        mvc.perform(put(path + "/" + supplier).cookie(suppliersEditor).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"defaultPercent\":30,\"rules\":[]}"))
                .andExpect(status().isOk());
    }

    private int marginCount(String supplierId, boolean deleted) {
        return jdbc.queryForObject("select count(*) from supplier_margins where supplier_id = ?::uuid and "
                + (deleted ? "deleted_at is not null" : "deleted_at is null"), Integer.class, supplierId);
    }

    private void createNomenclature() {
        String nomenclatureImport = jdbc.queryForObject("""
                insert into nomenclature_imports (store_id, file_name, total_rows, created_count, updated_count,
                    missing_count)
                values (?::uuid, 'nomenklatury.pdf', 4, 4, 0, 0) returning id""", String.class, storeId);
        for (String code : java.util.List.of("01", "0101", "02", "0201")) {
            jdbc.update("""
                    insert into nomenclatures (store_id, code, level, parent_id, name, last_import_id)
                    values (?::uuid, ?, ?, (select id from nomenclatures where store_id = ?::uuid and code = ?), ?,
                        ?::uuid)""", storeId, code, code.length() == 2 ? "REGION" : "FAMILY", storeId,
                    code.substring(0, code.length() - 2), "Pozycja " + code, nomenclatureImport);
        }
    }

    private int count(String supplierId, boolean deleted) {
        return jdbc.queryForObject("select count(*) from supplier_sale_prices where supplier_id = ?::uuid and "
                + (deleted ? "deleted_at is not null" : "deleted_at is null"), Integer.class, supplierId);
    }

    private String createSupplier(String name) throws Exception {
        String json = mvc.perform(post("/api/stores/" + storeId + "/suppliers").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private String createStore() throws Exception {
        String number = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999_999L));
        String json = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep " + number + "\",\"active\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private Cookie employee(String permissions) throws Exception {
        String roleJson = "{\"id\":\"" + TestRanks.role(jdbc, permissions) + "\"}";
        String username = "sp" + UUID.randomUUID().toString().substring(0, 8);
        String userId = jdbc.queryForObject("""
                insert into users (username, display_name, password_hash, role_id)
                values (?, 'Pracownik', ?, ?::uuid) returning id""", String.class,
                username, passwordEncoder.encode("haslo-pracownika"), JsonPath.read(roleJson, "$.id"));
        TestRanks.join(jdbc, userId, storeId);
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"haslo-pracownika\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }
}
