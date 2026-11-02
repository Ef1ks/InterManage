package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.intermanage.backend.auth.security.JwtService;
import com.intermanage.backend.meat.component.MeatScanReader;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/**
 * Zakup mięsa: odczyt skanu WZ (model podmieniony), rozpoznanie dostawcy po NIP-ie i zapamiętanym numerze
 * weterynaryjnym, nauka przypisań pozycji, zapis, duplikat, usuwanie i porównanie sklepów z ceną sprzedaży z dnia
 * dostawy albo ostatnią znaną.
 */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class MeatPurchaseIntegrationTests {

    private static final String FILLET_ITM8 = "0000099058426";
    private static final String FILLET_LABEL = "FILET SWIEZY KL A | KURCZAK";
    private static final byte[] PDF = "%PDF-1.4 skan WZ".getBytes();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @MockitoBean
    MeatScanReader reader;

    private Cookie owner;
    private String storeA;
    private String storeB;
    private String filletA;
    private String filletB;
    private String waldiA;
    private String waldiB;

    @BeforeEach
    void setUp() throws Exception {
        owner = login();
        storeA = createStore();
        storeB = createStore();
        filletA = insertMeat(storeA);
        filletB = insertMeat(storeB);
        waldiA = insertSupplier(storeA, "71", "WALDI ZAKLADY MIESNE SP. Z O.O. SP.K.", "7880012512");
        waldiB = insertSupplier(storeB, "71", "WALDI ZAKLADY MIESNE SP. Z O.O. SP.K.", "7880012512");
        insertSupplier(storeA, "16538", "SCAPR DDD16538 BAZA REFAKTURA", "7821977018");
    }

    @Test
    void scanRecognizesSupplierLearnsAndReportComparesStores() throws Exception {
        // Pierwszy skan: dostawca po NIP-ie z dokumentu, filet wskazany przez model, surowiec bez artykułu.
        when(reader.read(any(), anyString(), anyList(), anyList())).thenAnswer(invocation -> {
            List<String> articles = invocation.getArgument(2);
            return new MeatScanReader.Reading("WALDI ZAKŁADY MIĘSNE", "788-00-12-512", "00001268", "PL 30050201 WE",
                    0, "WZ/HDI: 18067/10/2026/M", LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 28), List.of(
                            new MeatScanReader.Line("Filet z kurczaka-luz *A", null, new BigDecimal("13.49"), false,
                                    null, articles.indexOf(FILLET_LABEL) + 1),
                            new MeatScanReader.Line("II A 80/20", "0", new BigDecimal("10.70"), true,
                                    "ilość poprawiona", 0)));
        });
        String scan = mvc.perform(multipart(url(storeA, "/scan")).file(pdf()).cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplier.id").value(waldiA))
                .andExpect(jsonPath("$.supplierMatch").value("NIP"))
                .andExpect(jsonPath("$.readVetNumber").value("PL30050201WE"))
                .andExpect(jsonPath("$.readBdo").value("1268"))
                .andExpect(jsonPath("$.deliveryDate").value("2026-10-28"))
                .andExpect(jsonPath("$.duplicateId").value(nullValue()))
                .andExpect(jsonPath("$.lines[0].article.itm8").value(FILLET_ITM8))
                .andExpect(jsonPath("$.lines[0].match").value("MODEL"))
                .andExpect(jsonPath("$.lines[1].article").value(nullValue()))
                .andExpect(jsonPath("$.lines[1].match").value("NONE"))
                .andExpect(jsonPath("$.lines[1].handwrittenCorrection").value(true))
                .andReturn().getResponse().getContentAsString();
        String scanId = JsonPath.read(scan, "$.scanId");

        mvc.perform(get(url(storeA, "/scans/" + scanId)).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"));

        String body = deliveryJson(scanId, waldiA, "WZ/HDI: 18067/10/2026/M", "2026-10-27", filletA, "13.49");
        String created = mvc.perform(post(url(storeA, "")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.lines[1].article").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        String deliveryId = JsonPath.read(created, "$.id");

        // Ten sam dokument drugi raz (numer bez względu na wielkość liter) daje 409.
        mvc.perform(post(url(storeA, "")).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("WZ/HDI", "wz/hdi")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.duplicateId").value(deliveryId));

        // Drugi skan z uciętym nagłówkiem: dostawca po zapamiętanym numerze weterynaryjnym, pozycje z przypisań.
        when(reader.read(any(), anyString(), anyList(), anyList())).thenReturn(new MeatScanReader.Reading(null, null,
                null, "PL30050201 WE", 0, "WZ/HDI: 18067/10/2026/M", LocalDate.of(2026, 10, 27), null, List.of(
                        new MeatScanReader.Line("FILET Z KURCZAKA-LUZ *A", null, new BigDecimal("13.99"), false, null,
                                0),
                        new MeatScanReader.Line("II A 80/20", null, new BigDecimal("10.90"), false, null, 0))));
        mvc.perform(multipart(url(storeA, "/scan")).file(pdf()).cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplier.id").value(waldiA))
                .andExpect(jsonPath("$.supplierMatch").value("MARK"))
                .andExpect(jsonPath("$.deliveryDate").value("2026-10-27"))
                .andExpect(jsonPath("$.duplicateId").value(deliveryId))
                .andExpect(jsonPath("$.lines[0].article.id").value(filletA))
                .andExpect(jsonPath("$.lines[0].match").value("LINK"))
                .andExpect(jsonPath("$.lines[1].match").value("LINK"))
                .andExpect(jsonPath("$.lines[1].noArticle").value(true));

        // Drugi sklep kupił ten sam filet drożej; sprzedaży z dnia dostawy jeszcze nie ma.
        mvc.perform(post(url(storeB, "")).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(deliveryJson(null, waldiB, "WZ 1", "2026-10-27", filletB, "14.10")))
                .andExpect(status().isCreated());
        String salesImportA = salesImport(storeA);
        insertSale(storeA, salesImportA, "2026-10-27", "10", "239.90");
        insertSale(storeA, salesImportA, "2026-10-26", "5", "100.00");
        insertSale(storeB, salesImport(storeB), "2026-10-25", "4", "99.96");

        String report = mvc.perform(get(url(storeA, "/report")).cookie(owner)
                        .param("from", "2026-10-20").param("to", "2026-10-31")
                        .param("stores", storeA, storeB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores", hasSize(2)))
                .andExpect(jsonPath("$.rows", hasSize(2)))
                // Marka KURCZAK przed pozycją bez artykułu.
                .andExpect(jsonPath("$.rows[0].itm8").value(FILLET_ITM8))
                .andExpect(jsonPath("$.rows[0].date").value("2026-10-27"))
                .andExpect(jsonPath("$.rows[0].vatPercent").value(5))
                .andExpect(jsonPath("$.rows[1].itm8").value(nullValue()))
                .andExpect(jsonPath("$.rows[1].name").value("II A 80/20"))
                .andReturn().getResponse().getContentAsString();
        // Sklepy idą po numerze, więc komórki szukamy po id sklepu.
        String a = "$.rows[%d].cells[?(@.storeId == '" + storeA + "')].";
        String b = "$.rows[%d].cells[?(@.storeId == '" + storeB + "')].";
        assertThat(JsonPath.<List<Double>>read(report, a.formatted(0) + "purchasePrice")).containsExactly(13.49);
        assertThat(JsonPath.<List<Double>>read(report, a.formatted(0) + "salePrice")).containsExactly(23.99);
        assertThat(JsonPath.<List<Boolean>>read(report, a.formatted(0) + "saleFallback")).containsExactly(false);
        assertThat(JsonPath.<List<Double>>read(report, b.formatted(0) + "purchasePrice")).containsExactly(14.10);
        assertThat(JsonPath.<List<Double>>read(report, b.formatted(0) + "salePrice")).containsExactly(24.99);
        assertThat(JsonPath.<List<Boolean>>read(report, b.formatted(0) + "saleFallback")).containsExactly(true);
        assertThat(JsonPath.<List<String>>read(report, b.formatted(0) + "saleDate")).containsExactly("2026-10-25");
        assertThat(JsonPath.<List<Double>>read(report, a.formatted(1) + "purchasePrice")).containsExactly(10.70);
        assertThat(JsonPath.<List<Object>>read(report, a.formatted(1) + "salePrice")).containsExactly((Object) null);
        assertThat(JsonPath.<List<Integer>>read(report, b.formatted(1) + "purchaseCount")).containsExactly(1);

        // Pracownik ze sklepem A porównuje tylko swój sklep; sklepu B nie dostanie.
        Cookie employee = employee("{\"MEAT_PURCHASES\":\"VIEW\"}", storeA);
        mvc.perform(get(url(storeA, "/report")).cookie(employee)
                        .param("from", "2026-10-20").param("to", "2026-10-31").param("mode", "AVERAGE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].date").value(nullValue()));
        mvc.perform(get(url(storeA, "/report")).cookie(employee)
                        .param("from", "2026-10-20").param("to", "2026-10-31").param("stores", storeB))
                .andExpect(status().isForbidden());
        mvc.perform(multipart(url(storeA, "/scan")).file(pdf()).cookie(employee).with(csrf()))
                .andExpect(status().isForbidden());

        // Usunięcie to znacznik: dokument znika z listy i raportu, „Przywróć” go oddaje.
        mvc.perform(delete(url(storeA, "/" + deliveryId)).cookie(owner).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get(url(storeA, "")).cookie(owner).param("from", "2026-10-01").param("to", "2026-10-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(post(url(storeA, "/" + deliveryId + "/restore")).cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedAt").value(nullValue()));
        mvc.perform(get(url(storeA, "")).cookie(owner).param("from", "2026-10-01").param("to", "2026-10-31"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].lineCount").value(2))
                .andExpect(jsonPath("$[0].hasScan").value(true));
    }

    @Test
    void rejectsUnsupportedFileAndReportsUnavailableModel() throws Exception {
        mvc.perform(multipart(url(storeA, "/scan"))
                        .file(new MockMultipartFile("file", "wz.txt", "text/plain", "tekst".getBytes()))
                        .cookie(owner).with(csrf()))
                .andExpect(status().isBadRequest());
        when(reader.read(any(), anyString(), anyList(), anyList()))
                .thenThrow(new MeatScanReader.Unavailable("Model jest chwilowo przeciążony"));
        mvc.perform(multipart(url(storeA, "/scan")).file(pdf()).cookie(owner).with(csrf()))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(get(url(storeA, "/articles")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].brand").value("KURCZAK"));
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "Scan_0004.pdf", "application/pdf", PDF);
    }

    private static String deliveryJson(String scanId, String supplierId, String number, String date,
            String productId, String price) {
        return """
                {"scanId":%s,"supplierId":"%s","documentNumber":"%s","deliveryDate":"%s",
                 "vetNumber":"PL 30050201 WE","bdo":"00001268","nip":"7880012512","lines":[
                  {"documentName":"Filet z kurczaka-luz *A","unitPrice":%s,"productId":"%s"},
                  {"documentName":"II A 80/20","documentCode":"0","unitPrice":10.70,"productId":null,
                   "handwrittenCorrection":true}]}"""
                .formatted(scanId == null ? "null" : "\"" + scanId + "\"", supplierId, number, date, price, productId);
    }

    private String url(String storeId, String path) {
        return "/api/stores/" + storeId + "/meat-deliveries" + path;
    }

    private String insertMeat(String storeId) {
        String productImport = jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, total_rows, created_count, updated_count,
                    unchanged_count, missing_count)
                values (?::uuid, 'test.xlsx', 2, 2, 0, 0, 0) returning id""", String.class, storeId);
        // Przyprawa z rejonu 24 (2401) nie jest mięsem do wyboru.
        jdbc.update("""
                insert into products (store_id, itm8, ean, name, brand, unit, sale_price, nomenclature, active,
                    first_seen_import_id, last_seen_import_id)
                values (?::uuid, '0000000004545', '0000000004545', 'KMINEK MIELONY', 'NASZ WYROB', 'kg', 9.99,
                    '24 01 01', true, ?::uuid, ?::uuid)""", storeId, productImport, productImport);
        return jdbc.queryForObject("""
                insert into products (store_id, itm8, ean, name, brand, unit, sale_price, nomenclature, vat_code,
                    active, first_seen_import_id, last_seen_import_id)
                values (?::uuid, ?, '2432680000000', 'FILET SWIEZY KL A', 'KURCZAK', 'kg', 23.99, '46 02 02', 6,
                    true, ?::uuid, ?::uuid) returning id""", String.class, storeId, FILLET_ITM8, productImport,
                productImport);
    }

    private String insertSupplier(String storeId, String code, String name, String nip) {
        return jdbc.queryForObject("""
                insert into suppliers (store_id, code, keyword, name, nip)
                values (?::uuid, ?, 'MIESO', ?, ?) returning id""", String.class, storeId, code, name, nip);
    }

    private String salesImport(String storeId) {
        return jdbc.queryForObject("""
                insert into sales_imports (store_id, file_name, report_generated_at, period_from, period_to,
                    total_rows, created_count, existing_count, conflict_count)
                values (?::uuid, 'sprzedaz.xlsx', timestamp '2026-10-28 07:00:00', date '2026-10-01',
                    date '2026-10-27', 2, 2, 0, 0) returning id""", String.class, storeId);
    }

    private void insertSale(String storeId, String importId, String date, String quantity, String gross) {
        jdbc.update("""
                insert into sales (store_id, sale_date, itm8, ean, quantity, cost_value, sale_value_gross, vat_value,
                    margin_value, import_id)
                values (?::uuid, ?::date, ?, null, ?, 0, ?, 0, 0, ?::uuid)""",
                storeId, date, FILLET_ITM8, new BigDecimal(quantity), new BigDecimal(gross), importId);
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

    /** Pracownik z rolą o podanych uprawnieniach, przypisany do sklepu. */
    private Cookie employee(String permissions, String store) throws Exception {
        String roleJson = mvc.perform(post("/api/settings/roles").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rola " + UUID.randomUUID() + "\",\"permissions\":" + permissions + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String username = "mp" + UUID.randomUUID().toString().substring(0, 8);
        String userId = jdbc.queryForObject("""
                insert into users (username, display_name, password_hash, role_id)
                values (?, 'Pracownik', ?, ?::uuid) returning id""", String.class,
                username, passwordEncoder.encode("haslo-pracownika"), JsonPath.read(roleJson, "$.id"));
        jdbc.update("insert into user_stores (user_id, store_id) values (?::uuid, ?::uuid)", userId, store);
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
