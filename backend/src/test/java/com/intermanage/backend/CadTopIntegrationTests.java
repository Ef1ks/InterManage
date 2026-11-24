package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.intermanage.backend.auth.security.JwtService;
import com.intermanage.backend.cadtop.service.CadPriceService;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.component.XlsxWriter;
import com.intermanage.backend.mea.component.MurphysoftClient;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** TOP-y cadencier i plan z portalu: pobranie raz, wybór obowiązującego z planu, zastąpienia i ostrzeżenia. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.secret-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class CadTopIntegrationTests {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final List<String> TOP_HEADER = List.of("nr_cad", "itm8", "EAN", "OPIS produktu", "MARKA",
            "KOD DOSTAWCY", "TYP PRODUKTU", "CENA ZAKUPU", "CENA SPRZEDAŻY", "sztuk w colis", "stawka VAT", "TOPY",
            "KOD NOMENKLATURY", "kat", "NOWOŚCI vs ostanie CAD / InterCAD", "MARŻA ADH", "reg_mov_siec");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    CadPriceService prices;

    @MockitoBean
    MurphysoftClient client;

    private final LocalDate today = LocalDate.now(StoreTime.ZONE);
    /** Każdy test ma własny rejon (litery), bo pliki TOP są wspólne dla sklepów rejonu. */
    private final String region = "rejon" + ThreadLocalRandom.current().ints(8, 'a', 'z' + 1)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append);
    private Cookie owner;
    private String storeId;

    @BeforeEach
    void prepare() throws Exception {
        // Plany są wspólne dla wszystkich sklepów, a aktywny jest jeden na rok.
        jdbc.update("delete from cad_plan_entries");
        jdbc.update("delete from cad_plans");
        jdbc.update("delete from cad_plan_checks");
        when(client.authorize(anyInt(), anyString()))
                .thenReturn(new MurphysoftClient.Token("token", Instant.now().plusSeconds(3600)));
        when(client.cadList(anyInt(), anyString())).thenReturn(json.readTree("{\"top\":[],\"info\":\"ok\"}"));
        when(client.cadFile(anyInt(), anyString(), anyString())).thenThrow(
                new MurphysoftClient.Unavailable("Portal Grupy Muszkieterów odpowiedział błędem (HTTP 401)"));
        owner = login();
        storeId = createStore();
        mvc.perform(put("/api/stores/" + storeId + "/mea/settings").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"klucz\",\"apiSecret\":\"\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void downloadsTopsOnceAndPlanDecidesWhichIsCurrent() throws Exception {
        String planPath = "htmldownload/cad_planing_" + today.getYear() + "_pdv.xlsx";
        portalFile(planPath, plan(List.of(
                entry("900", today.minusDays(40)), entry("900_1", today.minusDays(10)),
                entry("", today.minusDays(5)), entry("901", today.plusDays(5)), entry("901_1", today.plusDays(40)))));
        portalFile("htmldownload/261001_" + region + ".xlsx", top("900_1", "00000453", "BTop", "X"));
        portalFile("htmldownload/261002_" + region + ".xlsx", top("901", "00000453", "B ", ""));
        tops(file("cad900_1", "261001", "2026-10-01 10:00:00"), file("cad901", "261002", "2026-10-02 10:00:00"));

        sync().andExpect(jsonPath("$.downloadedTops").value(2))
                .andExpect(jsonPath("$.downloadedPlans").value(1))
                .andExpect(jsonPath("$.overview.region").value(region))
                .andExpect(jsonPath("$.overview.warnings", hasSize(0)))
                .andExpect(jsonPath("$.overview.plans", hasSize(1)))
                .andExpect(jsonPath("$.overview.plans[0].fromPortal").value(true))
                .andExpect(jsonPath("$.overview.rows[?(@.nrCad == '900')].status", hasItem("PAST")))
                .andExpect(jsonPath("$.overview.rows[?(@.nrCad == '900_1')].status", hasItem("CURRENT")))
                .andExpect(jsonPath("$.overview.rows[?(@.nrCad == '900_1')].endsOn",
                        hasItem(today.plusDays(4).toString())))
                .andExpect(jsonPath("$.overview.rows[?(@.nrCad == '900_1')].file.btopCount", hasItem(1)))
                .andExpect(jsonPath("$.overview.rows[?(@.nrCad == '900_1')].file.newCount", hasItem(1)))
                .andExpect(jsonPath("$.overview.rows[?(@.nrCad == '901')].status", hasItem("UPCOMING")))
                .andExpect(jsonPath("$.overview.rows[?(@.nrCad == '901')].file.bcount").doesNotExist())
                .andExpect(jsonPath("$.overview.rows[?(@.nrCad == '901')].file.bCount", hasItem(1)));
        // Nowszy plik (901) jeszcze nie obowiązuje.
        assertThat(prices.currentOn(UUID.fromString(storeId), today)).map(current -> current.file().getNrCad())
                .contains("900_1");
        assertThat(prices.currentOn(UUID.fromString(storeId), today.plusDays(5)))
                .map(current -> current.file().getNrCad()).contains("901");
        assertThat(jdbc.queryForMap("""
                select a.itm8, a.ean, a.name, a.purchase_price, a.sale_price, a.pack, a.vat_percent, a.top_mark,
                       a.subfamily_code, a.is_new, a.margin_percent, a.network_rotation
                from cad_top_articles a join cad_top_files f on f.id = a.file_id
                where f.region = ? and f.nr_cad = '900_1'""", region).toString())
                .isEqualTo("{itm8=0000000000453, ean=5900983014894, name=SERNIK, purchase_price=5.4500, "
                        + "sale_price=6.49, pack=6, vat_percent=8.00, top_mark=BTop, subfamily_code=011006, "
                        + "is_new=true, margin_percent=8.62, network_rotation=1.1300}");

        // Te same pliki nie pobierają się drugi raz.
        sync().andExpect(jsonPath("$.downloadedTops").value(0)).andExpect(jsonPath("$.downloadedPlans").value(0));

        // Poprawiony plik tego samego numeru zastępuje poprzedni, a numer spoza planu daje ostrzeżenie.
        portalFile("htmldownload/261003_" + region + ".xlsx", top("901", "00000604", "A", ""));
        portalFile("htmldownload/261004_" + region + ".xlsx", top("999", "00000604", "A", ""));
        tops(file("cad901", "261003", "2026-10-03 10:00:00"), file("cad999", "261004", "2026-10-04 10:00:00"));
        sync().andExpect(jsonPath("$.downloadedTops").value(2))
                .andExpect(jsonPath("$.overview.rows[?(@.nrCad == '901')].file.aCount", hasItem(1)))
                .andExpect(jsonPath("$.overview.rows[0].status").value("NOT_PLANNED"))
                .andExpect(jsonPath("$.overview.warnings", hasItem(containsString("spoza planu (999)"))));
        assertThat(jdbc.queryForObject("select count(*) from cad_top_files where region = ? and nr_cad = '901' "
                + "and replaced_at is not null", Integer.class, region)).isEqualTo(1);
    }

    @Test
    void warnsAboutMissingPlanAndMissingFile() throws Exception {
        portalFile("htmldownload/261001_" + region + ".xlsx", top("900", "00000453", "", ""));
        tops(file("cad900", "261001", "2026-10-01 10:00:00"));

        sync().andExpect(jsonPath("$.overview.warnings", hasItem(containsString("Brak planu cadencier na dziś"))));
        assertThat(prices.currentOn(UUID.fromString(storeId), today)).isEmpty();

        upload(plan(List.of(entry("900", today.minusDays(3)), entry("901", today.plusDays(2)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans[0].fromPortal").value(false))
                .andExpect(jsonPath("$.warnings", hasItem(containsString("Cadencier 901 wchodzi"))))
                .andExpect(jsonPath("$.warnings", hasItem(containsString("Plan kończy się na cadencier 901"))));
        // Ten sam plan drugi raz.
        upload(plan(List.of(entry("900", today.minusDays(3)), entry("901", today.plusDays(2)))))
                .andExpect(status().isConflict());
    }

    @Test
    void articleActiveInCadencierShowsItsCurrentPrice() throws Exception {
        portalFile("htmldownload/261001_" + region + ".xlsx", top("900", "00000453", "", ""));
        tops(file("cad900", "261001", "2026-10-01 10:00:00"));
        upload(plan(List.of(entry("900", today.minusDays(3)), entry("901", today.plusDays(2)))))
                .andExpect(status().isOk());
        sync();
        String productImport = jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, total_rows, created_count, updated_count,
                    unchanged_count, missing_count)
                values (?::uuid, 'test.xlsx', 3, 3, 0, 0, 0) returning id""", String.class, storeId);
        String cadencierImport = jdbc.queryForObject("""
                insert into cadencier_imports (store_id, file_name, total_rows) values (?::uuid, 'cad.xlsx', 2)
                returning id""", String.class, storeId);
        // Ten sam ITM8, ten sam EAN pod lokalnym ITM8 i artykuł spoza listy cadencier sklepu.
        product("0000000000453", "5900000000001", productImport);
        product("0000099000001", "5900983014894", productImport);
        product("0000099000002", "5900983014894", productImport);
        for (String itm8 : List.of("0000000000453", "0000099000001")) {
            jdbc.update("insert into cadencier_items (store_id, itm8, import_id) values (?::uuid, ?, ?::uuid)",
                    storeId, itm8, cadencierImport);
        }

        for (String itm8 : List.of("0000000000453", "0000099000001")) {
            article(itm8).andExpect(jsonPath("$.cadencier").value(true))
                    .andExpect(jsonPath("$.purchasePrice").value(1.0))
                    .andExpect(jsonPath("$.cadencierCurrent.nrCad").value("900"))
                    .andExpect(jsonPath("$.cadencierCurrent.from").value(today.minusDays(3).toString()))
                    .andExpect(jsonPath("$.cadencierCurrent.purchasePrice").value(5.45))
                    .andExpect(jsonPath("$.cadencierCurrent.salePrice").value(6.49));
        }
        article("0000099000002").andExpect(jsonPath("$.cadencier").value(false))
                .andExpect(jsonPath("$.cadencierCurrent").value(nullValue()));

        // Cadencier obowiązuje w całej sieci: sklep bez rejonu ma plik najliczniejszego rejonu.
        jdbc.update("delete from cad_store_regions");
        jdbc.update("insert into cad_store_regions (store_id, region, updated_at) values (?::uuid, ?, now())",
                createStore(), region);
        article("0000000000453").andExpect(jsonPath("$.cadencierCurrent.nrCad").value("900"));
    }

    @Test
    void listsCardsWhoseCadencierItm8IsNotPriority() throws Exception {
        List<List<Object>> rows = List.of(
                topRow("900", "00000453", "5900000000453"), topRow("900", "00000600", "5900000000600"),
                topRow("900", "00000601", "5900000000600"), topRow("900", "00000700", "5900000000700"),
                topRow("900", "00000701", "5900000000700"), topRow("900", "00000800", "2485390000003"),
                topRow("900", "00000900", "5900000000900"));
        portalFile("htmldownload/261001_" + region + ".xlsx", write(TOP_HEADER, rows));
        tops(file("cad900", "261001", "2026-10-01 10:00:00"));
        upload(plan(List.of(entry("900", today.minusDays(3)), entry("901", today.plusDays(2)))))
                .andExpect(status().isOk());
        sync();
        String oldImport = productImport(today.minusDays(1) + " 07:00");
        String productImport = productImport(today + " 00:00");
        // Ustawiony; lokalny ITM8 z dwoma ITM8 z cadencier; karta z innym ITM8 z cadencier; kod wagi; poza raportem.
        product("0000000000453", "5900000000453", productImport);
        product("0000099000600", "5900000000600", productImport);
        product("0000000000600", "5900000000600", oldImport);
        product("0000000000700", "5900000000700", productImport);
        product("0000099000800", "2485390000000", productImport);
        product("0000099000900", "5900000000900", oldImport);

        String url = "/api/stores/" + storeId + "/cadencier-priorities";
        mvc.perform(get(url).cookie(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.nrCad").value("900"))
                .andExpect(jsonPath("$.topCount").value(7))
                .andExpect(jsonPath("$.setCount").value(2))
                .andExpect(jsonPath("$.variantCount").value(1))
                .andExpect(jsonPath("$.absentCount").value(1))
                .andExpect(jsonPath("$.macroReady").value(false))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].itm8").value("0000099000600"))
                .andExpect(jsonPath("$.items[0].topItm8").value("0000000000600"))
                .andExpect(jsonPath("$.items[0].topKnown").value(true))
                .andExpect(jsonPath("$.items[0].match").value("MAIN_EAN"))
                .andExpect(jsonPath("$.items[0].status").value("TODO"))
                .andExpect(jsonPath("$.items[0].alternatives[0].itm8").value("0000000000601"))
                .andExpect(jsonPath("$.items[1].itm8").value("0000099000800"))
                .andExpect(jsonPath("$.items[1].match").value("SCALE_CODE"));

        String body = "{\"items\":[{\"itm8\":\"0000099000600\",\"topItm8\":\"0000000000601\"}]}";
        mvc.perform(post(url + "/macro").cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isConflict());
        mvc.perform(post(url + "/runs").cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("0000000000601", "0000000000453"))).andExpect(status().isConflict());
        String result = mvc.perform(post(url + "/runs").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.items[0].status").value("WAITING"))
                .andExpect(jsonPath("$.report.items[0].topItm8").value("0000000000601"))
                .andExpect(jsonPath("$.report.items[0].run.macro").value(false))
                .andReturn().getResponse().getContentAsString();
        String ids = "{\"ids\":[\"" + JsonPath.read(result, "$.runIds[0]") + "\"]}";
        mvc.perform(post(url + "/runs/undo").cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(ids)).andExpect(jsonPath("$.items[0].status").value("TODO"));
        mvc.perform(post(url + "/runs/restore").cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(ids)).andExpect(jsonPath("$.items[0].status").value("WAITING"));

        // Nowszy raport kartoteki dalej z lokalnym ITM8: nieustawione; z ITM8 z cadencier: znika z listy.
        String nextImport = productImport(today.plusDays(1) + " 07:00");
        jdbc.update("update products set last_seen_import_id = ?::uuid where last_seen_import_id = ?::uuid",
                nextImport, productImport);
        mvc.perform(get(url).cookie(owner)).andExpect(jsonPath("$.items[0].status").value("FAILED"));
        jdbc.update("update products set itm8 = '0000000000601' where store_id = ?::uuid and itm8 = '0000099000600'",
                storeId);
        mvc.perform(get(url).cookie(owner)).andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].itm8").value("0000099000800"));
    }

    private String productImport(String generatedAt) {
        return jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, report_generated_at, total_rows, created_count,
                    updated_count, unchanged_count, missing_count)
                values (?::uuid, 'test.xlsx', ?::timestamp, 3, 3, 0, 0, 0) returning id""", String.class, storeId,
                generatedAt);
    }

    private static List<Object> topRow(String number, String itm8, String ean) {
        return Arrays.asList(number, itm8, ean, "SERNIK", "DELECTA", "538", "E", "5.45", "6.49", "6", "8", "B",
                "01 10 06", "1", "", "0.08", "1.13");
    }

    private void product(String itm8, String ean, String importId) {
        jdbc.update("""
                insert into products (store_id, itm8, ean, name, unit, net_content, purchase_price, sale_price, active,
                    first_seen_import_id, last_seen_import_id)
                values (?::uuid, ?, ?, 'SERNIK', 'szt.', 1, 1, 7.49, true, ?::uuid, ?::uuid)""",
                storeId, itm8, ean, importId, importId);
    }

    private org.springframework.test.web.servlet.ResultActions article(String itm8) throws Exception {
        return mvc.perform(get("/api/stores/" + storeId + "/reports/articles/" + itm8).cookie(owner))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsOtherFileAsPlan() throws Exception {
        upload(new XlsxWriter().write("Arkusz", List.of(new XlsxWriter.Column("EAN", 10)), List.of(List.of("123"))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/stores/" + storeId + "/imports/cadencier-tops").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true));
    }

    private org.springframework.test.web.servlet.ResultActions sync() throws Exception {
        return mvc.perform(post("/api/stores/" + storeId + "/imports/cadencier-tops/sync").cookie(owner).with(csrf()))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions upload(byte[] file) throws Exception {
        return mvc.perform(multipart("/api/stores/" + storeId + "/imports/cadencier-tops/plan")
                .file(new MockMultipartFile("file", "cad_planing.xlsx", "application/octet-stream", file))
                .cookie(owner).with(csrf()));
    }

    private void portalFile(String path, byte[] bytes) {
        doReturn(bytes).when(client).cadFile(anyInt(), anyString(), eq(path));
    }

    private void tops(String... files) {
        JsonNode list = json.readTree("{\"top\":[" + String.join(",", files) + "],\"info\":\"ok\"}");
        when(client.cadList(anyInt(), anyString())).thenReturn(list);
    }

    private String file(String cad, String date, String updated) {
        return """
                {"id":"1","name":"%s_Rejon - produkty z cad. oraz z oznaczeniem TOP.xlsx","update_date":"%s",
                "path":"htmldownload/%s_%s.xlsx"}""".formatted(cad, updated, date, region);
    }

    private static List<Object> entry(String number, LocalDate from) {
        return Arrays.asList(number, DAY.format(from), "", "nowości + zmiany cen");
    }

    private static byte[] plan(List<List<Object>> rows) {
        return write(List.of("nr_cad", "data poczatku", "data konca", "komentarz"), rows);
    }

    private static byte[] top(String number, String itm8, String mark, String isNew) {
        return write(TOP_HEADER, List.of(Arrays.asList(number, itm8, "5900983014894", "SERNIK", "DELECTA", "538", "E",
                "5.45", "6.49", "6", "8", mark, "01 10 06", "1", isNew, "0.08617245905381514", "1.1300000000000006")));
    }

    private static byte[] write(List<String> header, List<List<Object>> rows) {
        return new XlsxWriter().write("Arkusz",
                header.stream().map(name -> new XlsxWriter.Column(name, 12)).toList(), new ArrayList<>(rows));
    }

    private String createStore() throws Exception {
        String number = String.valueOf(ThreadLocalRandom.current().nextInt(12_000, 19_999));
        String body = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep TOP " + number
                                + "\",\"active\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }
}
