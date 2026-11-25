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
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.InflaterInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

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
        assertThat(file.byItm8(6893790L)).containsExactly("MASŁO EXTRA 200G");

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

    /** Czyta plik tak jak program kolektora (Catalog.cs). */
    private static final class Reader {
        final int size;
        final Map<String, String> header;
        final List<List<String>> rows = new ArrayList<>();
        final List<String> search = new ArrayList<>();
        private final long[] eans;
        private final int[] eanRows;
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
