package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.intermanage.backend.auth.security.JwtService;
import com.intermanage.backend.common.component.XlsxReader;
import com.intermanage.backend.common.component.XlsxWriter;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** Marża wymagana na nomenklaturze: zapis i dziedziczenie, plik do uzupełnienia i jego wgranie, cena sugerowana. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.ksef.sync-enabled=false"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class NomenclatureMarginIntegrationTests {

    private static final String[][] NOMENCLATURE = {{"01", "REGION", "DELIKATESY"}, {"0101", "FAMILY", "CHRUPKI"},
            {"010105", "SUBFAMILY", "CHIPSY"}, {"010106", "SUBFAMILY", "INNE"}, {"25", "REGION", "NABIAL"},
            {"2542", "FAMILY", "MLEKO"}, {"254201", "SUBFAMILY", "MLEKO UHT"}};

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    XlsxWriter xlsxWriter;

    @Autowired
    XlsxReader xlsxReader;

    private Cookie owner;
    private String storeId;

    @BeforeEach
    void setUp() throws Exception {
        owner = login();
        storeId = createStore();
        String nomenclatureImport = jdbc.queryForObject("""
                insert into nomenclature_imports (store_id, file_name, total_rows, created_count, updated_count,
                    missing_count)
                values (?::uuid, 'nomenklatury.pdf', 7, 7, 0, 0) returning id""", String.class, storeId);
        for (String[] item : NOMENCLATURE) {
            jdbc.update("""
                    insert into nomenclatures (store_id, code, level, name, last_import_id, parent_id)
                    values (?::uuid, ?, ?, ?, ?::uuid, (select n.id from nomenclatures n
                        where n.store_id = ?::uuid and n.code = left(?, length(?) - 2)))""",
                    storeId, item[0], item[1], item[2], nomenclatureImport, storeId, item[0], item[0]);
        }
    }

    @Test
    void savesMarginsWithInheritance() throws Exception {
        save("""
                [{"code":"01","marginPercent":25},{"code":"010105","marginPercent":30}]""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == '010106')].marginPercent").value(hasItem((Object) null)))
                .andExpect(jsonPath("$[?(@.code == '010106')].effectivePercent").value(hasItem(25.0)))
                .andExpect(jsonPath("$[?(@.code == '010105')].effectivePercent").value(hasItem(30.0)))
                .andExpect(jsonPath("$[?(@.code == '254201')].effectivePercent").value(hasItem((Object) null)))
                .andExpect(jsonPath("$[?(@.code == '01')].updatedBy").value(hasItem("Właściciel")));

        // Wprowadzanie danych dostaje obowiązującą marżę przy każdej pozycji.
        mvc.perform(get("/api/stores/" + storeId + "/data-entry/nomenclatures").cookie(owner))
                .andExpect(jsonPath("$[?(@.code == '0101')].requiredMargin").value(hasItem(25.0)))
                .andExpect(jsonPath("$[?(@.code == '010105')].requiredMargin").value(hasItem(30.0)));

        // Pusta wartość czyści próg, kod spoza nomenklatury i marża 100 są odrzucane.
        save("""
                [{"code":"010105","marginPercent":null}]""")
                .andExpect(jsonPath("$[?(@.code == '010105')].effectivePercent").value(hasItem(25.0)));
        save("""
                [{"code":"999999","marginPercent":20}]""").andExpect(status().isBadRequest());
        save("""
                [{"code":"01","marginPercent":100}]""").andExpect(status().isBadRequest());
    }

    @Test
    void templateAndImport() throws Exception {
        save("""
                [{"code":"25","marginPercent":18.5}]""").andExpect(status().isOk());
        byte[] template = mvc.perform(get(url("/template")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("marze_wymagane_")))
                .andReturn().getResponse().getContentAsByteArray();
        List<List<String>> rows = read(template);
        assertThat(rows.getFirst()).containsExactly("Kod", "Poziom", "Nazwa", "Marża %");
        assertThat(rows).hasSize(NOMENCLATURE.length + 1);
        assertThat(rows.get(5)).containsExactly("25", "Rejon", "NABIAL", "18.5");

        // Uzupełniony plik: kod jako liczba bez zera wiodącego, przecinek i znak procentu.
        importFile(file(List.of(
                List.of("1", "Rejon", "DELIKATESY", "20"), List.of("0101", "Rodzina", "CHRUPKI", ""),
                List.of("010105", "Podrodzina", "CHIPSY", "32,5%"), List.of("010106", "Podrodzina", "INNE", ""),
                List.of("25", "Rejon", "NABIAL", ""), List.of("2542", "Rodzina", "MLEKO", "15"),
                List.of("254201", "Podrodzina", "MLEKO UHT", ""))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changedCount").value(4))
                .andExpect(jsonPath("$.withMarginCount").value(3));
        mvc.perform(get(url("")).cookie(owner))
                .andExpect(jsonPath("$[?(@.code == '010106')].effectivePercent").value(hasItem(20.0)))
                .andExpect(jsonPath("$[?(@.code == '010105')].effectivePercent").value(hasItem(32.5)))
                .andExpect(jsonPath("$[?(@.code == '25')].marginPercent").value(hasItem((Object) null)))
                .andExpect(jsonPath("$[?(@.code == '254201')].effectivePercent").value(hasItem(15.0)));

        // Brakująca pozycja, nieznany kod i format procentowy Excela odrzucają cały plik.
        importFile(file(List.of(List.of("01", "Rejon", "DELIKATESY", "10"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rowErrorCount").value(NOMENCLATURE.length - 1));
        List<List<String>> unknown = new ArrayList<>(allRows("10"));
        unknown.add(List.of("999999", "Podrodzina", "?", "10"));
        importFile(file(unknown)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rowErrors[0]").value("Nieznany kod 999999"));
        importFile(file(allRows("0.2"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rowErrors[0]").value(containsString("format procentowy")));
        mvc.perform(get(url("")).cookie(owner))
                .andExpect(jsonPath("$[?(@.code == '01')].marginPercent").value(hasItem(20.0)));
    }

    @Test
    void exportHasSuggestedPrice() throws Exception {
        save("""
                [{"code":"01","marginPercent":30}]""").andExpect(status().isOk());
        byte[] file = mvc.perform(post("/api/stores/" + storeId + "/data-entry/export/xlsx").cookie(owner)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invoiceNumber":"FV/1","sellerName":"X","rows":[
                                 {"ean":"5900000000012","quantity":1,"unitNet":2.5,"invoiceUnit":"szt.",
                                  "name":"CHIPSY 150G","vatPercent":5,"salePrice":3.49,"subfamilyCode":"010105",
                                  "unit":"kg","netContent":0.15,"known":false}]}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        List<List<String>> rows = read(file);
        assertThat(rows.getFirst().get(9)).isEqualTo("Cena sugerowana");
        // 2,50 / (1 − 30%) × 1,05 = 3,75
        assertThat(new BigDecimal(rows.get(1).get(9))).isEqualByComparingTo("3.75");
    }

    @Test
    void roundsSuggestedPriceByRanges() throws Exception {
        save("""
                [{"code":"01","marginPercent":30}]""").andExpect(status().isOk());
        saveRounding("""
                [{"priceFrom":5,"priceTo":10,"ending":0.99},{"priceFrom":1,"priceTo":5,"ending":0.49}]""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].priceFrom").value(1.0))
                .andExpect(jsonPath("$[1].ending").value(0.99))
                .andExpect(jsonPath("$[0].createdBy").value("Właściciel"));
        // Nachodzące przedziały i "do" niższe niż "od" są odrzucane; lista zostaje bez zmian.
        saveRounding("""
                [{"priceFrom":1,"priceTo":6,"ending":0.49},{"priceFrom":5,"priceTo":10,"ending":0.99}]""")
                .andExpect(status().isBadRequest());
        saveRounding("""
                [{"priceFrom":5,"priceTo":2,"ending":0.49}]""").andExpect(status().isBadRequest());
        mvc.perform(get("/api/stores/" + storeId + "/price-rounding").cookie(employee("{\"DATA_ENTRY\":\"VIEW\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        byte[] file = mvc.perform(post("/api/stores/" + storeId + "/data-entry/export/xlsx").cookie(owner)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invoiceNumber":"FV/1","sellerName":"X","rows":[
                                 {"ean":"1","quantity":1,"unitNet":2.5,"invoiceUnit":"szt.","name":"A",
                                  "vatPercent":5,"subfamilyCode":"010105","unit":"szt.","netContent":1,"known":false},
                                 {"ean":"2","quantity":1,"unitNet":4,"invoiceUnit":"szt.","name":"B",
                                  "vatPercent":5,"subfamilyCode":"010105","unit":"szt.","netContent":1,"known":false},
                                 {"ean":"3","quantity":1,"unitNet":4,"invoiceUnit":"szt.","name":"C",
                                  "vatPercent":5,"cadencierPrice":5.55,"subfamilyCode":"010105","unit":"szt.",
                                  "netContent":1,"known":true}]}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        List<List<String>> rows = read(file);
        // 3,75 w przedziale 1-5 z końcówką 0,49 -> 3,99; 4 / 0,7 × 1,05 = 6,00 w przedziale 5-10 -> 6,99.
        assertThat(new BigDecimal(rows.get(1).get(9))).isEqualByComparingTo("3.99");
        assertThat(new BigDecimal(rows.get(2).get(9))).isEqualByComparingTo("6.99");
        // CENA_CAD bez liczenia i zaokrąglania.
        assertThat(new BigDecimal(rows.get(3).get(9))).isEqualByComparingTo("5.55");

        // Pusta lista wyłącza zaokrąglanie, poprzednie przedziały zostają w bazie ze znacznikiem.
        saveRounding("[]").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        assertThat(jdbc.queryForObject(
                "select count(*) from price_rounding_rules where store_id = ?::uuid and deleted_at is not null",
                Integer.class, storeId)).isEqualTo(2);
    }

    private ResultActions saveRounding(String rules) throws Exception {
        return mvc.perform(put("/api/stores/" + storeId + "/price-rounding").cookie(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"rules\":" + rules + "}"));
    }

    @Test
    void permissions() throws Exception {
        Cookie dataEntry = employee("{\"DATA_ENTRY\":\"VIEW\"}");
        mvc.perform(get(url("")).cookie(dataEntry)).andExpect(status().isForbidden());
        mvc.perform(get("/api/stores/" + storeId + "/data-entry/nomenclatures").cookie(dataEntry))
                .andExpect(status().isOk());

        Cookie viewer = employee("{\"SALES\":\"VIEW\"}");
        mvc.perform(get(url("")).cookie(viewer)).andExpect(status().isOk());
        mvc.perform(get(url("/template")).cookie(viewer)).andExpect(status().isOk());
        mvc.perform(put(url("")).cookie(viewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"margins\":[]}")).andExpect(status().isForbidden());
    }

    private List<List<String>> allRows(String margin) {
        List<List<String>> rows = new ArrayList<>();
        for (String[] item : NOMENCLATURE) {
            rows.add(List.of(item[0], "", item[2], margin));
        }
        return rows;
    }

    private byte[] file(List<List<String>> rows) {
        return xlsxWriter.write("Marże", List.of(new XlsxWriter.Column("Kod", 10),
                new XlsxWriter.Column("Poziom", 10), new XlsxWriter.Column("Nazwa", 10),
                new XlsxWriter.Column("Marża %", 10)),
                rows.stream().map(row -> row.stream().map(value -> (Object) (value.isEmpty() ? null : value))
                        .toList()).toList());
    }

    private List<List<String>> read(byte[] content) throws Exception {
        Path temp = Files.createTempFile("margins-test-", ".xlsx");
        try {
            Files.write(temp, content);
            List<List<String>> rows = new ArrayList<>();
            xlsxReader.readFirstSheet(temp, (rowNumber, cells) -> rows.add(new ArrayList<>(cells)));
            return rows;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private ResultActions save(String margins) throws Exception {
        return mvc.perform(put(url("")).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"margins\":" + margins + "}"));
    }

    private ResultActions importFile(byte[] content) throws Exception {
        return mvc.perform(multipart(url("/import"))
                .file(new MockMultipartFile("file", "marze.xlsx", MediaType.APPLICATION_OCTET_STREAM_VALUE, content))
                .cookie(owner).with(csrf()));
    }

    private String url(String path) {
        return "/api/stores/" + storeId + "/nomenclature-margins" + path;
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
        String roleJson = mvc.perform(post("/api/settings/roles").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rola " + UUID.randomUUID() + "\",\"permissions\":" + permissions + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String username = "nm" + UUID.randomUUID().toString().substring(0, 8);
        String userId = jdbc.queryForObject("""
                insert into users (username, display_name, password_hash, role_id)
                values (?, 'Pracownik', ?, ?::uuid) returning id""", String.class,
                username, passwordEncoder.encode("haslo-pracownika"), JsonPath.read(roleJson, "$.id"));
        jdbc.update("insert into user_stores (user_id, store_id) values (?::uuid, ?::uuid)", userId, storeId);
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
