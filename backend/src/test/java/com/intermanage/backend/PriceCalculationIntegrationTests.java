package com.intermanage.backend;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.intermanage.backend.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class PriceCalculationIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void pricesIngredientsFromLastDeliveryAndCalculatesSalePrice() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String cheesecake = insertProduct(storeId, "SERNIK DOMOWY", "36 60 01", "kg", "1.000", "10.00");
        String otherDeli = insertProduct(storeId, "PIEROGI RUSKIE", "24 05 01", "kg", "1.000", "8.00");
        String flour = insertProduct(storeId, "MAKA TORTOWA 1KG", "01 02 03", "kg", "1.000", "2.41");
        // Cena zakupu dotyczy opakowania 55 g, więc kilogram kosztuje 1,69 / 0,055.
        String cream = insertProduct(storeId, "BITA SMIETANA 55G", "01 05 01", "kg", "0.055", "1.69");
        String eggs = insertProduct(storeId, "JAJA SWIEZE", "01 07 01", "szt.", "1.000", "0.80");
        // Mąka przyszła dwiema dostawami - liczy się nowsza (2,60), nie kartoteka (2,41).
        insertDelivery(storeId, "PZ1", LocalDateTime.of(2026, 10, 20, 0, 0), itm8(flour), "2.50");
        String newest = insertDelivery(storeId, "PZ2", LocalDateTime.of(2026, 10, 23, 0, 0), itm8(flour), "2.60");

        mvc.perform(get(url(storeId, "/finished-products")).cookie(owner))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].productId").value(hasItem(cheesecake)))
                .andExpect(jsonPath("$[*].productId").value(not(hasItem(flour))));
        mvc.perform(get(url(storeId, "/products")).param("query", "maka tort").cookie(owner))
                .andExpect(jsonPath("$[0].unitCost").value(2.60))
                .andExpect(jsonPath("$[0].priceSource").value("DELIVERY"))
                .andExpect(jsonPath("$[0].priceDocumentNumber").value("PZ2"));
        mvc.perform(get(url(storeId, "/products")).param("query", "smietana 55").cookie(owner))
                .andExpect(jsonPath("$[0].unitCost").value(30.7273))
                .andExpect(jsonPath("$[0].priceSource").value("CATALOG"));

        // Cały przepis na 3 kg: 6,50 + 15,3637 + 8,00 = 29,8637 zł, czyli 9,95 zł/kg; z narzutem 60% i VAT 5%
        // 16,72 zł, zaokrąglone w górę do 16,99.
        String json = send(owner, post(url(storeId, "")), body(cheesecake, "PER_BATCH", "3", 5,
                item(flour, "2.5"), item(cream, "0.5"), item(eggs, "10")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("SERNIK DOMOWY"))
                .andExpect(jsonPath("$.product.ean").isNotEmpty())
                .andExpect(jsonPath("$.markupPercent").value(60))
                .andExpect(jsonPath("$.totalCost").value(29.86))
                .andExpect(jsonPath("$.costPerKg").value(9.95))
                .andExpect(jsonPath("$.salePrice").value(16.99))
                .andExpect(jsonPath("$.items[0].priceDocumentId").value(newest))
                .andExpect(jsonPath("$.items[1].priceSource").value("CATALOG"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(json, "$.id");

        // Drugi raz ten sam wyrób - pomyłka; produkt spoza działów garmażu nie może być wyrobem.
        send(owner, post(url(storeId, "")), body(cheesecake, "PER_KG", null, 5, item(flour, "1")))
                .andExpect(status().isConflict());
        send(owner, post(url(storeId, "")), body(flour, "PER_KG", null, 5, item(eggs, "1")))
                .andExpect(status().isBadRequest());
        send(owner, post(url(storeId, "")), body(otherDeli, "PER_KG", null, 7, item(eggs, "1")))
                .andExpect(status().isBadRequest());

        // Nowa cena w kartotece nie zmienia kalkulacji, dopóki nikt nie odświeży cen - także przy zmianie VAT.
        jdbc.update("update products set purchase_price = 2.20 where id = ?::uuid", cream);
        send(owner, put(url(storeId, "/" + id)), body(cheesecake, "PER_BATCH", "3", 23,
                item(flour, "2.5"), item(cream, "0.5"), item(eggs, "10")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.costPerKg").value(9.95))
                .andExpect(jsonPath("$.salePrice").value(19.99));
        // Odświeżenie podnosi cenę z 19,99 na 22,99 (+15%), więc najpierw pyta; bez decyzji nic się nie zmienia.
        mvc.perform(post(url(storeId, "/refresh-prices/preview")).param("calculationId", id).cookie(owner).with(csrf()))
                .andExpect(jsonPath("$[0].previousCalculatedPrice").value(19.99))
                .andExpect(jsonPath("$[0].newCalculatedPrice").value(22.99))
                .andExpect(jsonPath("$[0].changePercent").value(15.0))
                .andExpect(jsonPath("$[0].needsDecision").value(true));
        refresh(owner, storeId, id).andExpect(status().isConflict());
        refresh(owner, storeId, id, decision(id, "ACCEPT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].costPerKg").value(11.50))
                .andExpect(jsonPath("$[0].salePrice").value(22.99));
        mvc.perform(get(url(storeId, "/" + id)).cookie(owner)).andExpect(jsonPath("$.items[1].unitCost").value(40));

        // Własna cena: idzie do eksportu zamiast wyliczonej.
        String salad = send(owner, post(url(storeId, "")), body(otherDeli, "PER_KG", null, 5, item(eggs, "10")))
                .andExpect(jsonPath("$.salePrice").value(13.99))
                .andReturn().getResponse().getContentAsString();
        String saladId = JsonPath.read(salad, "$.id");
        send(owner, put(url(storeId, "/" + id)), bodyWithPrice(cheesecake, "PER_BATCH", "3", 23, "24.49",
                item(flour, "2.5"), item(cream, "0.5"), item(eggs, "10")))
                .andExpect(jsonPath("$.salePrice").value(24.49))
                .andExpect(jsonPath("$.calculatedPrice").value(22.99))
                .andExpect(jsonPath("$.manualPrice").value(24.49))
                .andExpect(jsonPath("$.manualPriceSetBy").isNotEmpty());

        // Nowa dostawa mąki i droższe jaja: sernik ma własną cenę i nową wyliczoną (23,99), więc pyta; sałatka
        // zmienia się o 7% (13,99 -> 14,99) i odświeża się bez pytania. "Zostaw" trzyma własną cenę.
        insertDelivery(storeId, "PZ3", LocalDateTime.of(2026, 10, 24, 0, 0), itm8(flour), "3.00");
        jdbc.update("update products set purchase_price = 0.84 where id = ?::uuid", eggs);
        mvc.perform(post(url(storeId, "/refresh-prices/preview")).cookie(owner).with(csrf()))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.calculationId == '" + id + "')].needsDecision").value(true))
                .andExpect(jsonPath("$[?(@.calculationId == '" + id + "')].newCalculatedPrice").value(23.99))
                .andExpect(jsonPath("$[?(@.calculationId == '" + saladId + "')].needsDecision").value(false));
        refresh(owner, storeId, null, decision(id, "KEEP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].salePrice").value(24.49))
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].calculatedPrice").value(23.99))
                .andExpect(jsonPath("$[?(@.id == '" + saladId + "')].salePrice").value(14.99));

        // Jaja o połowę droższe: sałatka skacze o 40% - "Zostaw" zapisuje dotychczasową cenę jako własną;
        // sernik przyjmuje nową wyliczoną i traci własną cenę.
        jdbc.update("update products set purchase_price = 1.20 where id = ?::uuid", eggs);
        refresh(owner, storeId, null, decision(id, "ACCEPT"), decision(saladId, "KEEP"))
                .andExpect(jsonPath("$[?(@.id == '" + saladId + "')].salePrice").value(14.99))
                .andExpect(jsonPath("$[?(@.id == '" + saladId + "')].manualPrice").value(14.99))
                .andExpect(jsonPath("$[?(@.id == '" + saladId + "')].calculatedPrice").value(20.99))
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].salePrice").value(25.99))
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].manualPrice").value(Matchers.contains((Object) null)));
        mvc.perform(get(url(storeId, "/finished-products")).cookie(owner))
                .andExpect(jsonPath("$[?(@.productId == '" + cheesecake + "')].calculationId").value(id));

        mvc.perform(get(url(storeId, "/export/xlsx")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", Matchers.containsString("ceny-garmazu-")))
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

        // Usunięcie to znacznik, który da się cofnąć.
        mvc.perform(delete(url(storeId, "/" + id)).cookie(owner).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get(url(storeId, "")).cookie(owner)).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post(url(storeId, "/" + id + "/restore")).cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salePrice").value(25.99));
    }

    private ResultActions refresh(Cookie owner, String storeId, String calculationId, String... decisions)
            throws Exception {
        String calculation = calculationId == null ? "null" : "\"" + calculationId + "\"";
        return send(owner, post(url(storeId, "/refresh-prices")),
                "{\"calculationId\":" + calculation + ",\"decisions\":[" + String.join(",", decisions) + "]}");
    }

    private static String decision(String calculationId, String choice) {
        return "{\"calculationId\":\"" + calculationId + "\",\"choice\":\"" + choice + "\"}";
    }

    private static String bodyWithPrice(String productId, String mode, String batchYield, int vatRate,
            String manualPrice, String... items) {
        return """
                {"productId":"%s","mode":"%s","batchYield":%s,"vatRate":%d,"manualPrice":%s,"items":[%s]}"""
                .formatted(productId, mode, batchYield, vatRate, manualPrice, String.join(",", items));
    }

    @Test
    void validatesIngredientsAndBatchYield() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String salad = insertProduct(storeId, "MIZERIA", "36 60 04", "kg", "1.000", "12.17");
        String cucumber = insertProduct(storeId, "OGOREK", "02 01 01", "kg", "1.000", "4.00");

        send(owner, post(url(storeId, "")), body(salad, "PER_BATCH", null, 5, item(cucumber, "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.batchYield").exists());
        send(owner, post(url(storeId, "")), body(salad, "PER_KG", null, 5, item(cucumber, "1"), item(cucumber, "2")))
                .andExpect(status().isBadRequest());
        send(owner, post(url(storeId, "")), body(salad, "PER_KG", null, 5, item(cucumber, "0")))
                .andExpect(status().isBadRequest());
        String otherStore = createStore(owner);
        String foreign = insertProduct(otherStore, "OGOREK OBCY", "02 01 01", "kg", "1.000", "4.00");
        send(owner, post(url(storeId, "")), body(salad, "PER_KG", null, 5, item(foreign, "1")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(url(storeId, "/export/xlsx")).cookie(owner)).andExpect(status().isBadRequest());

        // Skład na 1 kg: 0,8 kg ogórka po 4 zł = 3,20 zł/kg -> 3,20 * 1,6 * 1,05 = 5,376 -> 5,99.
        send(owner, post(url(storeId, "")), body(salad, "PER_KG", "3", 5, item(cucumber, "0.8")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchYield").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.costPerKg").value(3.20))
                .andExpect(jsonPath("$.salePrice").value(5.99));
    }

    @Test
    void remembersPieceWeightOfIngredientsCountedInPieces() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String salad = insertProduct(storeId, "MIZERIA", "36 60 04", "kg", "1.000", "12.17");
        String coleslaw = insertProduct(storeId, "SURÓWKA Z OGÓRKA", "36 60 05", "kg", "1.000", "10.00");
        String cucumber = insertProduct(storeId, "OGOREK DLUGI SZT", "02 01 01", "szt.", "1.000", "3.70");
        String cream = insertProduct(storeId, "SMIETANA 18%", "01 05 01", "kg", "1.000", "8.00");

        // Waga sztuki nie zmienia kosztu (ogórek dalej 3,70 zł/szt.), zostaje zapamiętana dla produktu.
        send(owner, post(url(storeId, "")), body(salad, "PER_BATCH", "1", 5,
                item(cucumber, "4", "0.35"), item(cream, "0.2", "0.5")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalCost").value(16.40))
                .andExpect(jsonPath("$.items[0].pieceWeight").value(0.35))
                .andExpect(jsonPath("$.items[1].pieceWeight").value(Matchers.nullValue()));
        mvc.perform(get(url(storeId, "/products")).param("query", "ogorek dlugi").cookie(owner))
                .andExpect(jsonPath("$[0].pieceWeight").value(0.35));
        assertPieceWeightSetBy(cucumber);

        // Kolejna kalkulacja widzi tę wagę; wyczyszczenie pola usuwa ją z kartoteki.
        send(owner, post(url(storeId, "")), body(coleslaw, "PER_KG", null, 5, item(cucumber, "2", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].pieceWeight").value(Matchers.nullValue()));
        send(owner, post(url(storeId, "")), body(salad, "PER_KG", null, 5, item(cucumber, "1", "0")))
                .andExpect(status().isBadRequest());
    }

    private void assertPieceWeightSetBy(String productId) {
        Integer set = jdbc.queryForObject("""
                select count(*) from products
                where id = ?::uuid and piece_weight_set_by is not null and piece_weight_set_at is not null""",
                Integer.class, productId);
        assertEquals(1, set);
    }

    private static String item(String productId, String quantity, String pieceWeight) {
        return "{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + ",\"pieceWeight\":" + pieceWeight
                + "}";
    }

    private static String body(String productId, String mode, String batchYield, int vatRate, String... items) {
        return """
                {"productId":"%s","mode":"%s","batchYield":%s,"vatRate":%d,"items":[%s]}"""
                .formatted(productId, mode, batchYield, vatRate, String.join(",", items));
    }

    private static String item(String productId, String quantity) {
        return "{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}";
    }

    private ResultActions send(Cookie owner, MockHttpServletRequestBuilder request, String json) throws Exception {
        return mvc.perform(request.cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String url(String storeId, String path) {
        return "/api/stores/" + storeId + "/price-calculations" + path;
    }

    private String createStore(Cookie owner) throws Exception {
        String number = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999_999L));
        String json = send(owner, post("/api/settings/stores"),
                "{\"number\":\"" + number + "\",\"name\":\"Sklep " + number + "\",\"active\":true}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private String itm8(String productId) {
        return jdbc.queryForObject("select itm8 from products where id = ?::uuid", String.class, productId);
    }

    /** Produkt w kartotece sklepu, tak jakby przyszedł z importu raportu. */
    private String insertProduct(String storeId, String name, String nomenclature, String unit, String netContent,
            String purchasePrice) {
        String importId = jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, total_rows, created_count, updated_count,
                    unchanged_count, missing_count)
                values (?::uuid, 'test.xlsx', 1, 1, 0, 0, 0) returning id""", String.class, storeId);
        String itm8 = "%013d".formatted(ThreadLocalRandom.current().nextLong(1, 9_999_999_999_999L));
        return jdbc.queryForObject("""
                insert into products (store_id, itm8, ean, name, nomenclature, unit, net_content, purchase_price,
                    sale_price, active, first_seen_import_id, last_seen_import_id)
                values (?::uuid, ?, ?, ?, ?, ?, ?, ?, 9.99, true, ?::uuid, ?::uuid) returning id""",
                String.class, storeId, itm8, "59" + itm8.substring(2), name, nomenclature, unit,
                new BigDecimal(netContent), new BigDecimal(purchasePrice), importId, importId);
    }

    /** Przyjęcie towaru (PZ) z jedną linią, jak z raportu Wejścia/Wyjścia. */
    private String insertDelivery(String storeId, String number, LocalDateTime date, String itm8, String price) {
        String importId = jdbc.queryForObject("""
                insert into document_imports (store_id, file_name, total_lines, saved_lines, created_count,
                    updated_count, skipped_lines)
                values (?::uuid, 'Zeszyt2.xlsx', 1, 1, 1, 0, 0) returning id""", String.class, storeId);
        String documentId = jdbc.queryForObject("""
                insert into documents (store_id, type, number, document_date, line_count, purchase_value,
                    sale_value_gross, first_import_id, last_import_id)
                values (?::uuid, 'PZ', ?, ?, 1, 0, 0, ?::uuid, ?::uuid) returning id""",
                String.class, storeId, number, date, importId, importId);
        jdbc.update("""
                insert into document_lines (document_id, line_number, itm8, quantity, purchase_price)
                values (?::uuid, 1, ?, 10, ?)""", documentId, itm8, new BigDecimal(price));
        return documentId;
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }
}
