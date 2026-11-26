package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.InflaterInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.agent.entity.AgentMacroKind;
import com.intermanage.backend.agent.entity.AgentMacroStatus;
import com.intermanage.backend.agent.service.AgentMacroService;
import com.intermanage.backend.auth.security.JwtService;
import com.intermanage.backend.collector.component.CollectorCatalogFile;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** Kolektor: jednorazowy kod parowania, klucz, wersja danych i plik kartoteki z indeksami. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class CollectorIntegrationTests {

    private static final String BUTTER = "0000006893790";
    private static final String BREAD = "0000001234567";
    private static final String HAM = "0000099058426";
    private static final String GONE = "0000007777777";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transaction;

    private Cookie owner;
    private String storeId;
    private final LocalDate today = LocalDate.now(ZoneId.of("Europe/Warsaw"));

    @BeforeEach
    void setUp() throws Exception {
        owner = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
        String number = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        storeId = JsonPath.read(mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep kolektora\",\"active\":true}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void pairingCodeWorksOnceAndKeyUntilRemoval() throws Exception {
        String body = mvc.perform(post(url("")).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Kolektor 1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pairCode").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(body, "$[0].pairCode");
        String id = JsonPath.read(body, "$[0].id");
        assertThat(code).matches("\\d{8}");
        mvc.perform(get(url("")).cookie(owner)).andExpect(jsonPath("$[0].pairCode").doesNotExist());

        // Wygasły kod nie działa, nowy zastępuje poprzedni.
        jdbc.update("update store_collectors set pair_expires_at = now() - interval '1 second' where id = ?::uuid", id);
        pair(code).andExpect(status().isForbidden());
        String next = JsonPath.read(mvc.perform(post(url("/" + id + "/code")).cookie(owner).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$[0].pairCode");
        Map<String, String> paired = lines(pair(next).andExpect(status().isOk()).andReturn().getResponse());
        String key = paired.get("key");
        assertThat(key).startsWith("imk_");
        assertThat(paired).containsEntry("name", "Kolektor 1").containsEntry("storeName", "Sklep kolektora");
        assertThat(paired.get("time")).matches("\\d{14}");
        pair(next).andExpect(status().isForbidden());

        assertThat(lines(statusOf(key).andExpect(status().isOk()).andReturn().getResponse()).get("catalog"))
                .matches("[0-9a-f]{32}");
        statusOf("imk_zly").andExpect(status().isForbidden());
        mvc.perform(get("/api/collector/status")).andExpect(status().isForbidden());
        mvc.perform(get(url("")).cookie(owner))
                .andExpect(jsonPath("$[0].lastVersion").value("1.0.0"))
                .andExpect(jsonPath("$[0].deviceName").value("MC3190"));

        // Nowe parowanie tego samego kolektora unieważnia poprzedni klucz.
        String again = JsonPath.read(mvc.perform(post(url("/" + id + "/code")).cookie(owner).with(csrf()))
                .andReturn().getResponse().getContentAsString(), "$[0].pairCode");
        statusOf(key).andExpect(status().isOk());
        String newKey = lines(pair(again).andReturn().getResponse()).get("key");
        statusOf(key).andExpect(status().isForbidden());
        statusOf(newKey).andExpect(status().isOk());

        mvc.perform(delete(url("/" + id)).cookie(owner).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deletedAt").isNotEmpty());
        statusOf(newKey).andExpect(status().isForbidden());
        mvc.perform(post(url("/" + id + "/restore")).cookie(owner).with(csrf())).andExpect(status().isOk());
        statusOf(newKey).andExpect(status().isOk());
    }

    @Test
    void catalogHasArticlesOfNewestReportWithEveryCode() throws Exception {
        String old = productImport("2026-11-01 06:00:00");
        String newest = productImport("2026-11-02 06:00:00");
        product(GONE, "5900000000017", "STARY ARTYKUL", newest, old);
        product(BUTTER, "5900000000001", "MASŁO EXTRA 200G", newest, newest);
        product(BREAD, "5900000000002", "CHLEB ŻYTNI 500G", newest, newest);
        product(HAM, "2436010000000", "SZYNKA KRUCHA", newest, newest);
        jdbc.update("""
                update products set brand = 'ŁOWICZ', stock = -3.5, rotation = 12, nomenclature = '01 01 01',
                    promo_price = 4.99, promo_from = ?::date, promo_to = ?::date
                where store_id = ?::uuid and itm8 = ?""", today.minusDays(1), today.plusDays(3), storeId, BUTTER);
        jdbc.update("update products set active = false where store_id = ?::uuid and itm8 = ?", storeId, HAM);

        String codes = jdbc.queryForObject("""
                insert into product_code_imports (store_id, file_name, report_generated_at, total_rows)
                values (?::uuid, 'Multikody.xlsx', now(), 1) returning id""", String.class, storeId);
        code(codes, BUTTER, "0000080053828");
        // Multikod masła jest głównym EAN-em chleba: skan prowadzi do chleba.
        code(codes, BUTTER, "5900000000002");
        // Czteropak (główny kod, opakowanie 1), puszka 0,25 i kod sześciu sztuk zapisany jako 0,166.
        pack(codes, BREAD, "5900000000002", "1");
        pack(codes, BREAD, "5903538900765", "0.250");
        pack(codes, BREAD, "5903538900772", "0.166");
        pack(codes, BREAD, "5903538900789", "6");
        pack(codes, HAM, "2436010000000", "1");
        pack(codes, HAM, "2436010000109", "0.100");
        jdbc.update("update product_codes set sale_unit = 'kg' where store_id = ?::uuid and itm8 = ?", storeId, HAM);
        // Cenówka: puszka ma własną nazwę i kaucję, kod sześciu sztuk współczynnik ceny, chleb kaucję 0 zł.
        String deposits = jdbc.queryForObject("""
                insert into deposit_kind_imports (store_id, file_name, total_rows) values (?::uuid, 'kaucje.pdf', 2)
                returning id""", String.class, storeId);
        jdbc.update("""
                insert into deposit_kinds (store_id, code, name, vat_code, value, first_import_id, last_import_id)
                values (?::uuid, 70, 'KAUCJA PUSZKA METALOWA', 7, 0.50, ?::uuid, ?::uuid),
                       (?::uuid, 99, 'BEZ OPŁATY/KAUCJI', 7, 0, ?::uuid, ?::uuid)""",
                storeId, deposits, deposits, storeId, deposits, deposits);
        jdbc.update("""
                update product_codes set name = 'PIWO 0.5L PUSZ', deposit_code = 70
                where store_id = ?::uuid and ean = '5903538900765'""", storeId);
        jdbc.update("update product_codes set price_factor = 1.006 where store_id = ?::uuid and ean = '5903538900772'",
                storeId);
        jdbc.update("update products set deposit_code = 99 where store_id = ?::uuid and itm8 = ?", storeId, BREAD);
        jdbc.update("update products set deposit_code = 70 where store_id = ?::uuid and itm8 = ?", storeId, BUTTER);
        String promotions = jdbc.queryForObject("""
                insert into promotion_imports (store_id, file_name, total_rows) values (?::uuid, 'Taryfy.pdf', 1)
                returning id""", String.class, storeId);
        jdbc.update("""
                insert into promotions (store_id, itm8, name, start_date, end_date, regular_sale_price, promo_price,
                    first_import_id, last_import_id)
                values (?::uuid, ?, 'Promocja', ?::date, ?::date, 7.49, 5.99, ?::uuid, ?::uuid)""",
                storeId, BREAD, today.plusDays(2), today.plusDays(9), promotions, promotions);

        String key = newCollector();
        String version = lines(statusOf(key).andReturn().getResponse()).get("catalog");
        MockHttpServletResponse response = mvc.perform(get("/api/collector/catalog")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getHeader("X-Catalog-Version")).isEqualTo(version);
        Reader file = new Reader(response.getContentAsByteArray());
        assertThat(response.getHeader("X-Catalog-Size")).isEqualTo(String.valueOf(file.size));

        assertThat(file.header).containsEntry("version", version).containsEntry("count", "3")
                .containsEntry("report", "202611020600");
        // Najpierw aktywne, alfabetycznie; artykułu spoza najnowszego raportu nie ma.
        assertThat(file.rows).extracting(row -> row.get(2))
                .containsExactly("CHLEB ŻYTNI 500G", "MASŁO EXTRA 200G", "SZYNKA KRUCHA");
        assertThat(file.search).containsExactly("CHLEB ZYTNI 500G ", "MASLO EXTRA 200G LOWICZ", "SZYNKA KRUCHA ");

        Map<String, String> butter = file.article("MASŁO EXTRA 200G");
        assertThat(butter).containsEntry("itm8", BUTTER).containsEntry("stock", "-3.5").containsEntry("rotation", "12")
                .containsEntry("promoPrice", "4.99").containsEntry("promoTo", compact(today.plusDays(3)))
                .containsEntry("promoRegular", "7.49").containsEntry("subfamily", "010101").containsEntry("active", "1");
        assertThat(file.article("CHLEB ŻYTNI 500G")).containsEntry("promoPrice", "5.99")
                .containsEntry("promoFrom", compact(today.plusDays(2)));
        assertThat(file.article("SZYNKA KRUCHA")).containsEntry("active", "0").containsEntry("promoPrice", "");

        assertThat(file.byEan(5900000000001L)).containsExactly("MASŁO EXTRA 200G");
        assertThat(file.byEan(80053828L)).containsExactly("MASŁO EXTRA 200G");
        assertThat(file.byEan(5900000000002L)).containsExactly("CHLEB ŻYTNI 500G");
        assertThat(file.byEan(2436010000000L)).containsExactly("SZYNKA KRUCHA");
        assertThat(file.byEan(5900000000017L)).isEmpty();
        // Wielopak: najdrobniejszy kod sztuki (1/6); kod na kg (etykieta 100 g) nie jest sztuką wielopaku.
        assertThat(file.article("CHLEB ŻYTNI 500G")).containsEntry("multipack", "6");
        assertThat(file.article("MASŁO EXTRA 200G")).containsEntry("multipack", "");
        assertThat(file.article("SZYNKA KRUCHA")).containsEntry("multipack", "");
        assertThat(file.ratio(5900000000002L)).isEqualTo("1/1");
        assertThat(file.ratio(5903538900765L)).isEqualTo("1/4");
        assertThat(file.ratio(5903538900772L)).isEqualTo("1/6");
        assertThat(file.ratio(5903538900789L)).isEqualTo("6/1");
        assertThat(file.ratio(5900000000001L)).isEqualTo("1/1");
        assertThat(file.byItm8(6893790L)).containsExactly("MASŁO EXTRA 200G");
        // Cenówka: kaucja głównego EAN-u z kartoteki (0 zł = bez), a multikody z nazwą, mnożnikiem ceny i kaucją.
        assertThat(file.article("MASŁO EXTRA 200G")).containsEntry("deposit", "0.5;KAUCJA PUSZKA METALOWA");
        assertThat(file.article("CHLEB ŻYTNI 500G")).containsEntry("deposit", "");
        assertThat(file.article("CHLEB ŻYTNI 500G").get("codes").split("\\|")).contains(
                "5903538900765;0.25;0.5;KAUCJA PUSZKA METALOWA;PIWO 0.5L PUSZ",
                "5903538900772;0.166996;;;",
                "5903538900789;6;;;");

        mvc.perform(post("/api/collector/synced").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("version=" + version))
                .andExpect(status().isOk());
        mvc.perform(get(url("")).cookie(owner)).andExpect(jsonPath("$[0].catalogCurrent").value(true));

        // Nowy raport zmienia wersję danych.
        productImport(LocalDateTime.now().toString().substring(0, 19).replace('T', ' '));
        assertThat(lines(statusOf(key).andReturn().getResponse()).get("catalog")).isNotEqualTo(version);
        mvc.perform(get(url("")).cookie(owner)).andExpect(jsonPath("$[0].catalogCurrent").value(false));
    }

    @Test
    void listFromCollectorBecomesProtocol() throws Exception {
        String report = productImport("2026-11-02 06:00:00");
        product(BREAD, "5903538901205", "PIWO LOMZA 4X0,5L", report, report);
        product(BUTTER, "5900000000001", "MASLO EXTRA 200G", report, report);
        jdbc.update("update products set vat_code = 1, purchase_price = 10, sale_price = 16.49, brand = 'LOMZA' "
                + "where store_id = ?::uuid and itm8 = ?", storeId, BREAD);
        String key = newCollector();
        String id = UUID.randomUUID().toString();
        // 8 puszek = 2 czteropaki, do tego 3 czteropaki i masło.
        String lines = BREAD + "\t5903538900765\t5903538901205\tPIWO LOMZA 4X0,5L\tLOMZA\t8\t2\t1\n"
                + BREAD + "\t5903538901205\t5903538901205\tPIWO LOMZA 4X0,5L\tLOMZA\t3\t3\t2\n"
                + BUTTER + "\t5900000000001\t5900000000001\tMASLO EXTRA 200G\t\t1.5\t1.5\t3\n";
        sendList(key, id, lines).andExpect(status().isOk());
        // Urwane potwierdzenie: ta sama lista drugi raz niczego nie dubluje.
        sendList(key, id, lines).andExpect(status().isOk());
        sendList(key, UUID.randomUUID().toString(), "zepsute").andExpect(status().isBadRequest());
        sendList("imk_zly", UUID.randomUUID().toString(), lines).andExpect(status().isForbidden());

        String base = "/api/stores/" + storeId + "/collector-lists";
        mvc.perform(get(base).cookie(owner)).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id)).andExpect(jsonPath("$[0].lineCount").value(3))
                .andExpect(jsonPath("$[0].collectorName").value("Kolektor"))
                .andExpect(jsonPath("$[0].startedAt").value("2026-11-25T08:15:00"));
        mvc.perform(get(base + "/count").cookie(owner)).andExpect(jsonPath("$.waiting").value(1));
        mvc.perform(get(base + "/details?ids=" + id).cookie(owner))
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].quantity").value(5))
                .andExpect(jsonPath("$.rows[0].ean").value("5903538901205"))
                .andExpect(jsonPath("$.rows[0].scans.length()").value(2))
                .andExpect(jsonPath("$.rows[0].saleValue").value(82.45))
                // (16,49 / 1,23 - 10) / 16,49
                .andExpect(jsonPath("$.rows[0].marginPercent").value(20.66))
                .andExpect(jsonPath("$.rows[1].quantity").value(1.5))
                .andExpect(jsonPath("$.rows[1].scans.length()").value(0))
                .andExpect(jsonPath("$.totalQuantity").value(6.5));

        String body = "{\"ids\":[\"" + id + "\"]}";
        MockHttpServletResponse file = mvc.perform(post(base + "/protocols/LIQUIDATION").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(file.getContentAsString()).isEqualTo("5903538901205000005000\r\n5900000000001000001500\r\n");
        assertThat(file.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("PROTO1_");
        // Sam plik jeszcze niczego nie oznacza: dopiero potwierdzony zapis.
        mvc.perform(get(base + "/count").cookie(owner)).andExpect(jsonPath("$.waiting").value(1));
        String protocolId = JsonPath.read(mvc.perform(post(base + "/protocols/LIQUIDATION/saved").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + id + "\"],\"fileName\":\"PROTO1_x.Traite0\"}"))
                .andExpect(jsonPath("$[0].protocols[0].kind").value("LIQUIDATION"))
                .andReturn().getResponse().getContentAsString(), "$[0].protocols[0].id");
        mvc.perform(get(base + "/count").cookie(owner)).andExpect(jsonPath("$.waiting").value(0));

        assertThat(mvc.perform(get(base + "/export/pdf?ids=" + id).cookie(owner)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
        assertThat(mvc.perform(get(base + "/export/xlsx?ids=" + id).cookie(owner)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).startsWith("PK".getBytes(StandardCharsets.US_ASCII));

        mvc.perform(delete(base + "/" + id + "/protocols/" + protocolId).cookie(owner).with(csrf()))
                .andExpect(jsonPath("$[0].protocols.length()").value(0));
        mvc.perform(delete(base + "/" + id).cookie(owner).with(csrf())).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(base + "/details?ids=" + id).cookie(owner)).andExpect(status().isNotFound());
        mvc.perform(post(base + "/" + id + "/restore").cookie(owner).with(csrf()))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void promotionsFromCollectorGoToComputerAndLabels() throws Exception {
        String report = productImport("2026-11-02 06:00:00");
        product(BUTTER, "5900000000001", "MASLO EXTRA 200G", report, report);
        jdbc.update("update products set vat_code = 1, purchase_price = 7, sale_price = 16.49 "
                + "where store_id = ?::uuid and itm8 = ?", storeId, BUTTER);
        String key = newCollector();
        String id = UUID.randomUUID().toString();
        String end = compact(today.plusDays(7));
        String lines = BUTTER + "\t5900000000001\tMASLO EXTRA 200G\t\t11.54\t" + end + "\n";
        // Sklep bez komputera z agentem: zlecenie czeka w kolejce, kolektor dostaje „ok”.
        assertThat(lines(sendPromotion(key, id, "1", lines).andExpect(status().isOk()).andReturn().getResponse()))
                .containsEntry("macro", "ok");
        sendPromotion(key, id, "1", lines).andExpect(status().isOk());
        sendPromotion(key, UUID.randomUUID().toString(), "0", "zepsute").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from agent_macro_jobs where store_id = ?::uuid and kind = "
                + "'PROMO_ENTRY'", Integer.class, storeId)).isEqualTo(1);
        String macro = jdbc.queryForObject("select macro from agent_macro_jobs where store_id = ?::uuid",
                String.class, storeId);
        assertThat(macro).contains("WEBKEY|Mercalys|txtZ8_CodeArticle_TxtBox|^a5900000000001{Enter}",
                "WEBKEY|Mercalys|lstZ22_TVA_TxtBox|^a1{Tab}", "WEBKEY|Mercalys|txpZ25_PrixPromoPDV_TxtBox|^a11,54{Enter}");

        String base = "/api/stores/" + storeId + "/collector-promotions";
        mvc.perform(get(base).cookie(owner)).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].macroRequested").value(true))
                .andExpect(jsonPath("$[0].macro.status").value("QUEUED"));
        mvc.perform(get(base + "/" + id).cookie(owner))
                .andExpect(jsonPath("$.rows[0].regularPrice").value(16.49))
                .andExpect(jsonPath("$.rows[0].discountPercent").value(30))
                // (11,54 / 1,23 - 7) / 11,54
                .andExpect(jsonPath("$.rows[0].marginPercent").value(20.64));
        mvc.perform(post(base + "/" + id + "/macro").cookie(owner).with(csrf())).andExpect(status().isConflict());

        String other = UUID.randomUUID().toString();
        sendPromotion(key, other, "0", lines).andExpect(status().isOk());
        mvc.perform(get(base + "/count").cookie(owner)).andExpect(jsonPath("$.waiting").value(1));
        mvc.perform(post(base + "/" + other + "/inserted").cookie(owner).with(csrf()))
                .andExpect(jsonPath("$.insertedBy").isNotEmpty());
        mvc.perform(get(base + "/count").cookie(owner)).andExpect(jsonPath("$.waiting").value(0));

        // Udane makro: cenówki do kolejki plików sklepu.
        UUID job = jdbc.queryForObject("select macro_job_id from collector_promotions where id = ?::uuid",
                UUID.class, id);
        transaction.executeWithoutResult(status -> events.publishEvent(new AgentMacroService.MacroEnded(
                UUID.fromString(storeId), job, AgentMacroKind.PROMO_ENTRY, AgentMacroStatus.DONE)));
        String file = null;
        for (int i = 0; i < 50 && file == null; i++) {
            Thread.sleep(100);
            file = jdbc.query("""
                    select convert_from(q.content, 'UTF8') from collector_promotions p
                    join store_file_queue q on q.id = p.labels_file_id where p.id = ?::uuid""",
                    rs -> rs.next() ? rs.getString(1) : null, id);
        }
        assertThat(file).isEqualTo("59000000000010001\r\n");

        mvc.perform(delete(base + "/" + id).cookie(owner).with(csrf())).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post(base + "/" + id + "/restore").cookie(owner).with(csrf()))
                .andExpect(jsonPath("$.length()").value(2));
    }

    private ResultActions sendPromotion(String key, String id, String macro, String lines) throws Exception {
        return mvc.perform(post("/api/collector/promotions").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("id=" + id + "&started=20261125081500"
                        + "&start=&macro=" + macro + "&lines=" + URLEncoder.encode(lines, StandardCharsets.UTF_8)));
    }

    private ResultActions sendList(String key, String id, String lines) throws Exception {
        return mvc.perform(post("/api/collector/lists").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("id=" + id + "&started=20261125081500&lines="
                        + URLEncoder.encode(lines, StandardCharsets.UTF_8)));
    }

    @Test
    void collectorDownloadsNewProgramAndSetupIsPublic() throws Exception {
        String key = newCollector();
        String latest = lines(statusOf(key).andReturn().getResponse()).get("program");
        assertThat(latest).matches("\\d+(\\.\\d+)+");
        mvc.perform(get(url("")).cookie(owner)).andExpect(jsonPath("$[0].newVersion").value(latest));

        mvc.perform(get("/api/collector/program")).andExpect(status().isForbidden());
        Map<String, String> manifest = lines(mvc.perform(get("/api/collector/program")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)).andExpect(status().isOk()).andReturn().getResponse());
        assertThat(manifest).containsEntry("version", latest);
        assertThat(manifest.get("files").split(",")).containsExactlyInAnyOrder("Kolektor.exe", "BouncyCastle.dll");
        for (String name : manifest.get("files").split(",")) {
            byte[] content = mvc.perform(get("/api/collector/program/" + name + "?version=" + latest)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + key))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertThat(manifest.get(name + ".size")).isEqualTo(String.valueOf(content.length));
            assertThat(manifest.get(name + ".sha256")).isEqualTo(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)));
        }
        mvc.perform(get("/api/collector/program/Kolektor.exe?version=0.0.1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)).andExpect(status().isConflict());
        mvc.perform(get("/api/collector/program/program.properties?version=" + latest)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)).andExpect(status().isNotFound());

        byte[] setup = mvc.perform(get("/api/collector/setup")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(setup, 0, 2, StandardCharsets.US_ASCII)).isEqualTo("MZ");
    }

    @Test
    void storeWithoutCatalogHasNothingToDownload() throws Exception {
        mvc.perform(get("/api/collector/catalog").header(HttpHeaders.AUTHORIZATION, "Bearer " + newCollector()))
                .andExpect(status().isConflict());
    }

    private String newCollector() throws Exception {
        String code = JsonPath.read(mvc.perform(post(url("")).cookie(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Kolektor\"}"))
                .andReturn().getResponse().getContentAsString(), "$[0].pairCode");
        return lines(pair(code).andExpect(status().isOk()).andReturn().getResponse()).get("key");
    }

    private ResultActions pair(String code) throws Exception {
        return mvc.perform(post("/api/collector/pair").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("code=" + code + "&device=MC3190&version=1.0.0"));
    }

    private ResultActions statusOf(String key) throws Exception {
        return mvc.perform(get("/api/collector/status").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                .header("X-Collector-Version", "1.0.0"));
    }

    private static Map<String, String> lines(MockHttpServletResponse response) {
        return pairs(new String(response.getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    private static Map<String, String> pairs(String text) {
        Map<String, String> result = new HashMap<>();
        for (String line : text.split("\n")) {
            int at = line.indexOf('=');
            result.put(line.substring(0, at), line.substring(at + 1));
        }
        return result;
    }

    private String url(String rest) {
        return "/api/stores/" + storeId + "/collectors" + rest;
    }

    private static String compact(LocalDate day) {
        return day.toString().replace("-", "");
    }

    private String productImport(String generatedAt) {
        return jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, report_generated_at, total_rows, created_count,
                    updated_count, unchanged_count, missing_count)
                values (?::uuid, 'test.xlsx', ?::timestamp, 3, 3, 0, 0, 0) returning id""", String.class, storeId,
                generatedAt);
    }

    private void product(String itm8, String ean, String name, String firstImport, String lastImport) {
        jdbc.update("""
                insert into products (store_id, itm8, ean, name, unit, net_content, purchase_price, sale_price, active,
                    first_seen_import_id, last_seen_import_id)
                values (?::uuid, ?, ?, ?, 'szt.', 1, 1, 7.49, true, ?::uuid, ?::uuid)""",
                storeId, itm8, ean, name, firstImport, lastImport);
    }

    private void code(String importId, String itm8, String ean) {
        jdbc.update("""
                insert into product_codes (store_id, itm8, ean, prio, first_import_id, last_import_id)
                values (?::uuid, ?, ?, false, ?::uuid, ?::uuid)""", storeId, itm8, ean, importId, importId);
    }

    private void pack(String importId, String itm8, String ean, String pack) {
        jdbc.update("""
                insert into product_codes (store_id, itm8, ean, prio, sale_pack, first_import_id, last_import_id)
                values (?::uuid, ?, ?, ?, ?::numeric, ?::uuid, ?::uuid)""", storeId, itm8, ean, pack.equals("1"), pack,
                importId, importId);
    }

    /** Czyta plik tak jak program kolektora (Catalog.cs). */
    private static final class Reader {
        final int size;
        final Map<String, String> header;
        final List<List<String>> rows = new ArrayList<>();
        final List<String> search = new ArrayList<>();
        private final long[] eans;
        private final int[] eanRows;
        private final int[] numerators;
        private final int[] denominators;
        private final long[] itm8s;
        private final int[] itm8Rows;

        Reader(byte[] zipped) throws IOException {
            byte[] plain = new InflaterInputStream(new ByteArrayInputStream(zipped)).readAllBytes();
            size = plain.length;
            ByteBuffer in = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN);
            assertThat(text(in, 8)).isEqualTo(CollectorCatalogFile.MAGIC);
            header = pairs(text(in, in.getInt()));
            int count = in.getInt();
            int rowBytes = in.getInt();
            int searchBytes = in.getInt();
            int eanCount = in.getInt();
            int nameBytes = in.getInt();
            int[] rowStarts = ints(in, count + 1);
            byte[] rowData = bytes(in, rowBytes);
            byte[] searchData = bytes(in, searchBytes);
            for (int i = 0; i < count; i++) {
                rows.add(List.of(new String(rowData, rowStarts[i], rowStarts[i + 1] - rowStarts[i],
                        StandardCharsets.UTF_8).split("\t", -1)));
            }
            search.addAll(List.of(new String(searchData, StandardCharsets.US_ASCII).split("\n")));
            assertThat(search).hasSize(count);
            eans = longs(in, eanCount);
            eanRows = ints(in, eanCount);
            numerators = ints(in, eanCount);
            denominators = ints(in, eanCount);
            itm8s = longs(in, count);
            itm8Rows = ints(in, count);
            bytes(in, nameBytes);
            assertThat(in.remaining()).isZero();
            assertThat(eans).isSorted();
            assertThat(itm8s).isSorted();
        }

        Map<String, String> article(String name) {
            List<String> row = rows.stream().filter(item -> item.get(2).equals(name)).findFirst().orElseThrow();
            Map<String, String> result = new HashMap<>();
            for (int i = 0; i < row.size(); i++) {
                result.put(CollectorCatalogFile.FIELDS.get(i), row.get(i));
            }
            return result;
        }

        String ratio(long ean) {
            for (int i = 0; i < eans.length; i++) {
                if (eans[i] == ean) {
                    return numerators[i] + "/" + denominators[i];
                }
            }
            return "";
        }

        List<String> byEan(long ean) {
            return find(eans, eanRows, ean);
        }

        List<String> byItm8(long itm8) {
            return find(itm8s, itm8Rows, itm8);
        }

        private List<String> find(long[] keys, int[] indexes, long key) {
            List<String> result = new ArrayList<>();
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] == key) {
                    result.add(rows.get(indexes[i]).get(2));
                }
            }
            return result;
        }

        private static byte[] bytes(ByteBuffer in, int length) {
            byte[] result = new byte[length];
            in.get(result);
            return result;
        }

        private static String text(ByteBuffer in, int length) {
            return new String(bytes(in, length), StandardCharsets.UTF_8);
        }

        private static int[] ints(ByteBuffer in, int length) {
            int[] result = new int[length];
            for (int i = 0; i < length; i++) {
                result[i] = in.getInt();
            }
            return result;
        }

        private static long[] longs(ByteBuffer in, int length) {
            long[] result = new long[length];
            for (int i = 0; i < length; i++) {
                result[i] = in.getLong();
            }
            return result;
        }
    }
}
