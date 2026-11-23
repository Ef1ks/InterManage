package com.intermanage.backend;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
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

/** Sprzedaż za okres: drzewo nomenklatury z filtrami, artykuły podrodziny i wydruk. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class PeriodSalesIntegrationTests {

    private static final String MILK = "0000000000001";
    private static final String CHEESE = "0000000000002";
    private static final String BREAD = "0000000000003";
    private static final String NOT_IN_CATALOG = "0000000000005";
    private static final String PERIOD = "from=2026-10-01&to=2026-10-31";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private Cookie owner;
    private String store;
    private String salesImport;

    @BeforeEach
    void setUp() throws Exception {
        owner = login();
        store = createStore();
        salesImport = salesImport();
        product(MILK, "010101", "MLEKOVITA");
        product(CHEESE, "010102", "SERY");
        product(BREAD, "660101", "PIEKARNIA");
        sale(MILK, "2026-10-05", 10, "10.00", "30.00");
        sale(MILK, "2026-11-05", 99, "99.00", "999.00");
        sale(CHEESE, "2026-10-06", 2, "4.00", "10.00");
        sale(BREAD, "2026-10-07", 5, "5.00", "20.00");
        sale(NOT_IN_CATALOG, "2026-10-07", 1, "1.00", "2.00");
    }

    @Test
    void buildsTreeFromFilteredSalesOfPeriod() throws Exception {
        mvc.perform(get(url("")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articleCount").value(4))
                .andExpect(jsonPath("$.total.saleValueGross").value(62.00))
                .andExpect(jsonPath("$.regions[*].code", contains("01", "66", "")))
                .andExpect(jsonPath("$.regions[0].name").value("01_DELIKATESY"))
                .andExpect(jsonPath("$.regions[0].figures.saleValueGross").value(40.00))
                .andExpect(jsonPath("$.regions[0].children[0].code").value("0101"))
                .andExpect(jsonPath("$.regions[0].children[0].children[*].code", contains("010101", "010102")));

        mvc.perform(get(url("&brands=mlekovita")).cookie(owner))
                .andExpect(jsonPath("$.articleCount").value(1))
                .andExpect(jsonPath("$.total.saleValueGross").value(30.00))
                .andExpect(jsonPath("$.brands[*].name", contains("MLEKOVITA", "PIEKARNIA", "SERY")));

        mvc.perform(get(url("&nomenclatures=66")).cookie(owner))
                .andExpect(jsonPath("$.regions[*].code", contains("66")));

        mvc.perform(get(url("&query=artykul 2")).cookie(owner))
                .andExpect(jsonPath("$.regions[0].children[0].children[*].code", contains("010102")));
    }

    @Test
    void listsArticlesOfSubfamilyAndPrintsChosenDepth() throws Exception {
        mvc.perform(get("/api/stores/" + store + "/reports/period-sales/articles?" + PERIOD + "&parent=010101")
                        .cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].itm8").value(MILK))
                .andExpect(jsonPath("$[0].figures.quantity").value(10));
        mvc.perform(get("/api/stores/" + store + "/reports/period-sales/articles?" + PERIOD + "&parent=brak")
                        .cookie(owner))
                .andExpect(jsonPath("$[*].itm8", contains(NOT_IN_CATALOG)));

        for (String depth : new String[] {"REGION", "FAMILY", "SUBFAMILY", "ARTICLE"}) {
            byte[] pdf = mvc.perform(get("/api/stores/" + store + "/reports/period-sales/pdf?" + PERIOD
                            + "&brands=SERY&depth=" + depth).cookie(owner))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                    .andReturn().getResponse().getContentAsByteArray();
            assertEquals("%PDF", new String(pdf, 0, 4));
        }
        mvc.perform(get("/api/stores/" + store + "/reports/period-sales/pdf?" + PERIOD + "&brands=NIKT")
                .cookie(owner)).andExpect(status().isBadRequest());
    }

    /** Sprzedaż dzienna z tych samych danych: rejony i rodziny dzień po dniu, suma sklepu w każdym dniu, wydruk. */
    @Test
    void dailySalesSplitsRegionsAndFamiliesByDay() throws Exception {
        String daily = "/api/stores/" + store + "/reports/daily-sales?from=2026-10-05&to=2026-10-07";
        mvc.perform(get(daily).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days", contains("2026-10-05", "2026-10-06", "2026-10-07")))
                .andExpect(jsonPath("$.lastSaleDate").value("2026-10-07"))
                .andExpect(jsonPath("$.regions[*].code", contains("01", "66", "")))
                .andExpect(jsonPath("$.regions[0].days[0].saleValueGross").value(30.00))
                .andExpect(jsonPath("$.regions[0].days[1].saleValueGross").value(10.00))
                .andExpect(jsonPath("$.regions[0].days[2]").doesNotExist())
                .andExpect(jsonPath("$.regions[0].sum.saleValueGross").value(40.00))
                .andExpect(jsonPath("$.regions[0].children[*].code", contains("0101")))
                .andExpect(jsonPath("$.regions[2].days[2].saleValueGross").value(2.00))
                .andExpect(jsonPath("$.total.days[2].saleValueGross").value(22.00))
                .andExpect(jsonPath("$.total.sum.saleValueGross").value(62.00));

        for (String layout : new String[] {"DAYS_AS_ROWS", "DAYS_AS_COLUMNS"}) {
            byte[] pdf = mvc.perform(get(daily.replace("daily-sales?", "daily-sales/pdf?") + "&level=FAMILY"
                            + "&measure=MARGIN_PERCENT&layout=" + layout).cookie(owner))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                    .andReturn().getResponse().getContentAsByteArray();
            assertEquals("%PDF", new String(pdf, 0, 4));
        }
        mvc.perform(get("/api/stores/" + store + "/reports/daily-sales?from=2026-01-01&to=2026-10-31")
                .cookie(owner)).andExpect(status().isBadRequest());

        // Własne kategorie sklepu: kategoria sumuje swoje rejony, reszta i sprzedaż bez nomenklatury w „Pozostałe”.
        mvc.perform(post("/api/stores/" + store + "/reports/price-check/categories").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Świeże\",\"regionCodes\":[\"01\"]}"))
                .andExpect(status().isCreated());
        mvc.perform(get(daily + "&grouping=CATEGORY").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions[*].name", contains("Świeże", "Pozostałe")))
                .andExpect(jsonPath("$.regions[0].level").value("CATEGORY"))
                .andExpect(jsonPath("$.regions[0].sum.saleValueGross").value(40.00))
                .andExpect(jsonPath("$.regions[0].children[*].code", contains("01")))
                .andExpect(jsonPath("$.regions[1].code").value("pozostale"))
                .andExpect(jsonPath("$.regions[1].children[*].code", contains("66", "")))
                .andExpect(jsonPath("$.regions[1].sum.saleValueGross").value(22.00))
                .andExpect(jsonPath("$.total.sum.saleValueGross").value(62.00));
        mvc.perform(get(daily.replace("daily-sales?", "daily-sales/pdf?") + "&grouping=CATEGORY&level=FAMILY"
                + "&layout=DAYS_AS_ROWS").cookie(owner)).andExpect(status().isOk());
    }

    private String url(String query) {
        return "/api/stores/" + store + "/reports/period-sales?" + PERIOD + query;
    }

    private String salesImport() {
        String nomenclatureImport = jdbc.queryForObject("""
                insert into nomenclature_imports (store_id, file_name, total_rows, created_count, updated_count,
                    missing_count)
                values (?::uuid, 'nomenklatury.pdf', 1, 1, 0, 0) returning id""", String.class, store);
        jdbc.update("""
                insert into nomenclatures (store_id, code, level, name, last_import_id)
                values (?::uuid, '01', 'REGION', '01_DELIKATESY', ?::uuid)""", store, nomenclatureImport);
        return jdbc.queryForObject("""
                insert into sales_imports (store_id, file_name, report_generated_at, period_from, period_to,
                    total_rows, created_count, existing_count, conflict_count)
                values (?::uuid, 'sprzedaz.xlsx', timestamp '2026-11-10 07:00:00', date '2026-07-01',
                    date '2026-11-09', 1, 1, 0, 0) returning id""", String.class, store);
    }

    private void product(String itm8, String subfamily, String brand) {
        String code = subfamily.substring(0, 2) + " " + subfamily.substring(2, 4) + " " + subfamily.substring(4);
        String importId = jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, total_rows, created_count, updated_count,
                    unchanged_count, missing_count)
                values (?::uuid, 'test.xlsx', 1, 1, 0, 0, 0) returning id""", String.class, store);
        jdbc.update("""
                insert into products (store_id, itm8, ean, name, brand, nomenclature, active, first_seen_import_id,
                    last_seen_import_id)
                values (?::uuid, ?, ?, ?, ?, ?, true, ?::uuid, ?::uuid)""",
                store, itm8, itm8, "ARTYKUL " + itm8.replaceFirst("^0+", ""), brand, code, importId, importId);
    }

    /** Sprzedaż bez VAT, więc brutto = netto. */
    private void sale(String itm8, String day, int quantity, String cost, String gross) {
        jdbc.update("""
                insert into sales (store_id, sale_date, itm8, ean, quantity, cost_value, sale_value_gross, vat_value,
                    margin_value, import_id)
                values (?::uuid, ?::date, ?, ?, ?, ?::numeric, ?::numeric, 0, ?::numeric - ?::numeric, ?::uuid)""",
                store, day, itm8, itm8, quantity, cost, gross, gross, cost, salesImport);
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

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }
}
