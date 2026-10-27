package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.ThreadLocalRandom;

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

import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class DeliOrderFormIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void ownerBuildsFormFromSectionsCategoriesAndStoreProducts() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        // Identyfikatory to UUID v7 generowane w aplikacji (rosną w czasie, dobre dla indeksów).
        assertThat(java.util.UUID.fromString(storeId).version()).isEqualTo(7);
        String kabanos = insertProduct(storeId, "KABANOSY WIEPRZOWE 200G");
        insertProduct(storeId, "SZYNKA KONSERWOWA 1KG");

        String form = send(owner, post(url(storeId, "/sections")), "{\"name\":\"Rzeźnia\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].name").value("Rzeźnia"))
                .andReturn().getResponse().getContentAsString();
        String section = id(form, "$.sections[0].id");
        send(owner, post(url(storeId, "/sections")), "{\"name\":\"Garmaż\"}")
                .andExpect(jsonPath("$.sections[1].name").value("Garmaż"));
        send(owner, post(url(storeId, "/sections")), "{\"name\":\"rzeźnia\"}").andExpect(status().isConflict());

        form = send(owner, post(url(storeId, "/sections/" + section + "/categories")), "{\"name\":\"Wędliny\"}")
                .andExpect(jsonPath("$.sections[0].categories[0].name").value("Wędliny"))
                .andReturn().getResponse().getContentAsString();
        String category = id(form, "$.sections[0].categories[0].id");

        // Wyszukiwarka rusza od 3 znaków i szuka po fragmencie nazwy bez względu na wielkość liter.
        mvc.perform(get(url(storeId, "/products")).param("query", "ka").cookie(owner)).andExpect(status().isBadRequest());
        mvc.perform(get(url(storeId, "/products")).param("query", "kabanos").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(kabanos));
        // Słowa w dowolnej kolejności i jako fragmenty: "wiep kab" znajdzie "KABANOSY WIEPRZOWE 200G".
        mvc.perform(get(url(storeId, "/products")).param("query", "wiep kab").cookie(owner))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(kabanos));
        mvc.perform(get(url(storeId, "/products")).param("query", "kab szynka").cookie(owner))
                .andExpect(jsonPath("$.length()").value(0));

        String productJson = "{\"productId\":\"" + kabanos + "\"}";
        send(owner, post(url(storeId, "/categories/" + category + "/products")), productJson);
        form = send(owner, post(url(storeId, "/categories/" + category + "/products")), productJson)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].categories[0].products.length()").value(1))
                .andExpect(jsonPath("$.sections[0].categories[0].products[0].name").value("KABANOSY WIEPRZOWE 200G"))
                .andReturn().getResponse().getContentAsString();
        String item = id(form, "$.sections[0].categories[0].products[0].id");

        send(owner, put(url(storeId, "/categories/" + category)), "{\"name\":\"Wędliny i kiełbasy\"}")
                .andExpect(jsonPath("$.sections[0].categories[0].name").value("Wędliny i kiełbasy"));
        mvc.perform(delete(url(storeId, "/categories/" + category + "/products/" + item)).cookie(owner).with(csrf()))
                .andExpect(jsonPath("$.sections[0].categories[0].products.length()").value(0));

        mvc.perform(delete(url(storeId, "/sections/" + section)).cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.length()").value(1))
                .andExpect(jsonPath("$.sections[0].name").value("Garmaż"));
        assertThat(jdbc.queryForObject("select count(*) from order_categories where id = ?::uuid", Integer.class, category))
                .isZero();
    }

    @Test
    void pastedProductListAddsProductsByEanWithTheirUnits() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String otherStore = createStore(owner);
        String kabanos = insertProduct(storeId, "KABANOSY 1KG", "szt.");
        String pasztet = insertProduct(storeId, "PASZTET 200G", "kg");
        String foreign = insertProduct(otherStore, "SZYNKA 1KG");
        String kabanosEan = ean(kabanos);
        String pasztetEan = ean(pasztet);

        String form = send(owner, post(url(storeId, "/sections")), "{\"name\":\"Garmaż\"}")
                .andReturn().getResponse().getContentAsString();
        String section = id(form, "$.sections[0].id");
        form = send(owner, post(url(storeId, "/sections/" + section + "/categories")), "{\"name\":\"Wędliny\"}")
                .andReturn().getResponse().getContentAsString();
        String category = id(form, "$.sections[0].categories[0].id");
        send(owner, post(url(storeId, "/categories/" + category + "/products")), "{\"productId\":\"" + pasztet + "\"}");

        // Nowy produkt dochodzi na koniec, znany zostaje na miejscu z nową jednostką, EAN z innego sklepu jest nieznany.
        send(owner, post(url(storeId, "/categories/" + category + "/products/paste")), """
                {"rows":[{"ean":"%s","orderUnit":"KG"},{"ean":"%s","orderUnit":"SZT"},
                         {"ean":"%s","orderUnit":"KG"},{"ean":"0000000000000","orderUnit":"KG"}]}"""
                .formatted(kabanosEan, pasztetEan, ean(foreign)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.notFound.length()").value(2))
                .andExpect(jsonPath("$.form.sections[0].categories[0].products[0].productId").value(pasztet))
                .andExpect(jsonPath("$.form.sections[0].categories[0].products[0].orderUnit").value("SZT"))
                .andExpect(jsonPath("$.form.sections[0].categories[0].products[1].productId").value(kabanos))
                .andExpect(jsonPath("$.form.sections[0].categories[0].products[1].orderUnit").value("KG"));

        send(owner, post(url(storeId, "/categories/" + category + "/products/paste")), "{\"rows\":[]}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void formAndProductsStayWithinTheirStore() throws Exception {
        Cookie owner = login();
        String storeA = createStore(owner);
        String storeB = createStore(owner);
        String productOfB = insertProduct(storeB, "PASZTET DROBIOWY 500G");

        String form = send(owner, post(url(storeA, "/sections")), "{\"name\":\"Garmaż\"}")
                .andReturn().getResponse().getContentAsString();
        String sectionA = id(form, "$.sections[0].id");
        form = send(owner, post(url(storeA, "/sections/" + sectionA + "/categories")), "{\"name\":\"Pasztety\"}")
                .andReturn().getResponse().getContentAsString();
        String categoryA = id(form, "$.sections[0].categories[0].id");

        // Produkt z innego sklepu nie trafi do formularza, a działu sklepu A nie da się zmienić przez sklep B.
        send(owner, post(url(storeA, "/categories/" + categoryA + "/products")), "{\"productId\":\"" + productOfB + "\"}")
                .andExpect(status().isBadRequest());
        send(owner, put(url(storeB, "/sections/" + sectionA)), "{\"name\":\"Przejęty\"}").andExpect(status().isNotFound());
        mvc.perform(get(url(storeB, "/form")).cookie(owner)).andExpect(jsonPath("$.sections.length()").value(0));
    }

    @Test
    void invitedStoreOrdersAndSupplierCorrectsAndFulfills() throws Exception {
        Cookie owner = login();
        String supplier = createStore(owner);
        String shop = createStore(owner);
        String kabanos = insertProduct(supplier, "KABANOSY 1KG", "kg");
        String pasztet = insertProduct(supplier, "PASZTET 200G", "szt.");

        String form = send(owner, post(url(supplier, "/sections")), "{\"name\":\"Garmaż\"}").andReturn().getResponse()
                .getContentAsString();
        String section = id(form, "$.sections[0].id");
        form = send(owner, post(url(supplier, "/sections/" + section + "/categories")), "{\"name\":\"Wędliny\"}")
                .andReturn().getResponse().getContentAsString();
        String category = id(form, "$.sections[0].categories[0].id");
        send(owner, post(url(supplier, "/categories/" + category + "/products")), "{\"productId\":\"" + kabanos + "\"}");
        form = send(owner, post(url(supplier, "/categories/" + category + "/products")), "{\"productId\":\"" + pasztet + "\"}")
                .andExpect(jsonPath("$.sections[0].categories[0].products[0].orderUnit").value("KG"))
                .andExpect(jsonPath("$.sections[0].categories[0].products[1].orderUnit").value("SZT"))
                .andReturn().getResponse().getContentAsString();
        String kabanosItem = id(form, "$.sections[0].categories[0].products[0].id");
        String pasztetItem = id(form, "$.sections[0].categories[0].products[1].id");

        // Bez zaproszenia sklep nie widzi cudzego formularza.
        mvc.perform(get(url(shop, "/suppliers/" + supplier + "/form")).cookie(owner)).andExpect(status().isNotFound());

        String invitations = send(owner, post(url(supplier, "/invitations")), "{\"storeIds\":[\"" + shop + "\"]}")
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String invitation = id(invitations, "$[0].id");
        mvc.perform(get(url(shop, "/overview")).cookie(owner))
                .andExpect(jsonPath("$.pendingInvitations[0].formStore.id").value(supplier))
                .andExpect(jsonPath("$.suppliers.length()").value(0));
        mvc.perform(post(url(shop, "/received-invitations/" + invitation + "/accept")).cookie(owner).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get(url(shop, "/overview")).cookie(owner))
                .andExpect(jsonPath("$.suppliers[0].store.id").value(supplier))
                .andExpect(jsonPath("$.pendingInvitations.length()").value(0));

        String orderUrl = url(shop, "/suppliers/" + supplier + "/orders");
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Warsaw")).toString();
        send(owner, post(orderUrl), orderJson(today, kabanosItem, "1.25", pasztetItem, "1.5"))
                .andExpect(status().isBadRequest());
        send(owner, post(orderUrl), orderJson("2020-01-01", kabanosItem, "1.25", pasztetItem, "2"))
                .andExpect(status().isBadRequest());
        String order = send(owner, post(orderUrl), orderJson(today, kabanosItem, "1.25", pasztetItem, "2"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.items[0].sectionName").value("Garmaż"))
                .andExpect(jsonPath("$.items[0].unit").value("KG"))
                .andReturn().getResponse().getContentAsString();
        String orderId = id(order, "$.id");
        String kabanosLine = id(order, "$.items[0].id");

        mvc.perform(get(url(supplier, "/overview")).cookie(owner)).andExpect(jsonPath("$.newIncomingCount").value(1));

        // Pliki zamówienia: .lst z EAN-ami w kolejności, Excel i PDF.
        String kabanosEan = jdbc.queryForObject("select ean from products where id = ?::uuid", String.class, kabanos);
        String pasztetEan = jdbc.queryForObject("select ean from products where id = ?::uuid", String.class, pasztet);
        String lst = mvc.perform(get(url(shop, "/orders/" + orderId + "/export/lst")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".lst")))
                .andReturn().getResponse().getContentAsString();
        assertThat(lst).isEqualTo(kabanosEan + "\r\n" + pasztetEan + "\r\n");
        byte[] xlsx = mvc.perform(get(url(supplier, "/orders/" + orderId + "/export/xlsx")).cookie(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(xlsx, 0, 2)).isEqualTo("PK");
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(xlsx))) {
            java.util.zip.ZipEntry entry;
            String sheet = null;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("xl/worksheets/sheet1.xml")) {
                    sheet = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            assertThat(sheet).contains(">EAN<", ">Nazwa<", ">Marka<", ">Ilość<", kabanosEan);
        }
        byte[] pdf = mvc.perform(get(url(supplier, "/orders/" + orderId + "/export/pdf")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
        mvc.perform(get(url(shop, "/orders/" + orderId + "/export/doc")).cookie(owner)).andExpect(status().isNotFound());
        mvc.perform(get(url(supplier, "/orders/incoming")).cookie(owner))
                .andExpect(jsonPath("$[0].id").value(orderId))
                .andExpect(jsonPath("$[0].store.id").value(shop));

        send(owner, put(url(supplier, "/orders/" + orderId + "/items")),
                "{\"items\":[{\"id\":\"" + kabanosLine + "\",\"quantity\":1}]}")
                .andExpect(jsonPath("$.items[0].orderedQuantity").value(1.25))
                .andExpect(jsonPath("$.items[0].quantity").value(1))
                .andExpect(jsonPath("$.correctedAt").isNotEmpty());
        // Zamawiający nie poprawia ani nie realizuje zamówienia u dostawcy.
        mvc.perform(post(url(shop, "/orders/" + orderId + "/fulfill")).cookie(owner).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post(url(supplier, "/orders/" + orderId + "/fulfill")).cookie(owner).with(csrf()))
                .andExpect(jsonPath("$.status").value("FULFILLED"));
        send(owner, put(url(supplier, "/orders/" + orderId + "/items")),
                "{\"items\":[{\"id\":\"" + kabanosLine + "\",\"quantity\":2}]}")
                .andExpect(status().isConflict());
        mvc.perform(get(url(supplier, "/overview")).cookie(owner)).andExpect(jsonPath("$.newIncomingCount").value(0));
        mvc.perform(get(url(shop, "/orders/sent")).cookie(owner))
                .andExpect(jsonPath("$[0].corrected").value(true))
                .andExpect(jsonPath("$[0].status").value("FULFILLED"));

        // Pozycja poprawiona do zera nie trafia do plików.
        String pasztetLine = id(order, "$.items[1].id");
        String secondOrder = send(owner, post(orderUrl), orderJson(today, kabanosItem, "1", pasztetItem, "1"))
                .andReturn().getResponse().getContentAsString();
        String secondId = id(secondOrder, "$.id");
        String secondPasztet = id(secondOrder, "$.items[1].id");
        send(owner, put(url(supplier, "/orders/" + secondId + "/items")),
                "{\"items\":[{\"id\":\"" + secondPasztet + "\",\"quantity\":0}]}").andExpect(status().isOk());
        assertThat(mvc.perform(get(url(shop, "/orders/" + secondId + "/export/lst")).cookie(owner))
                .andReturn().getResponse().getContentAsString()).isEqualTo(kabanosEan + "\r\n");
        assertThat(pasztetLine).isNotBlank();

        // Pozycje spoza formularza: zamawiający szuka w kartotece dostawcy i dopisuje produkt w wybranej jednostce.
        String smalec = insertProduct(supplier, "SMALEC DOMOWY 250G", "szt.");
        mvc.perform(get(url(shop, "/suppliers/" + supplier + "/products")).param("query", "dom sma").cookie(owner))
                .andExpect(jsonPath("$[0].id").value(smalec));
        // Trafia do wybranego działu (kartka idzie do działu), za pozycjami z formularza.
        send(owner, post(orderUrl), "{\"deliveryDate\":\"" + today + "\",\"items\":[{\"itemId\":\"" + kabanosItem
                + "\",\"quantity\":1}],\"extras\":[{\"productId\":\"" + smalec + "\",\"sectionId\":\"" + section
                + "\",\"unit\":\"SZT\",\"quantity\":4}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].sectionName").value("Garmaż"))
                .andExpect(jsonPath("$.items[1].categoryName").value("Spoza formularza"))
                .andExpect(jsonPath("$.items[1].unit").value("SZT"));
        send(owner, post(orderUrl), "{\"deliveryDate\":\"" + today + "\",\"extras\":[{\"productId\":\""
                + smalec + "\",\"unit\":\"SZT\",\"quantity\":4}]}")
                .andExpect(status().isBadRequest());
        String foreignSection = id(send(owner, post(url(shop, "/sections")), "{\"name\":\"Obcy dział\"}")
                .andReturn().getResponse().getContentAsString(), "$.sections[0].id");
        send(owner, post(orderUrl), "{\"deliveryDate\":\"" + today + "\",\"extras\":[{\"productId\":\""
                + smalec + "\",\"sectionId\":\"" + foreignSection + "\",\"unit\":\"SZT\",\"quantity\":4}]}")
                .andExpect(status().isConflict());
        String foreign = insertProduct(shop, "OBCY PRODUKT", "kg");
        send(owner, post(orderUrl), "{\"deliveryDate\":\"" + today + "\",\"extras\":[{\"productId\":\""
                + foreign + "\",\"sectionId\":\"" + section + "\",\"unit\":\"KG\",\"quantity\":1}]}")
                .andExpect(status().isBadRequest());
        send(owner, post(orderUrl), "{\"deliveryDate\":\"" + today + "\",\"items\":[]}")
                .andExpect(status().isBadRequest());

        // Usunięcie ukrywa nowe zamówienie tylko po jednej stronie; druga widzi je jako usunięte. Nic nie znika z bazy.
        String withdrawn = id(send(owner, post(orderUrl), orderJson(today, kabanosItem, "1", pasztetItem, "1"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        String withdrawnFilter = "$[?(@.id == '" + withdrawn + "')]";
        mvc.perform(delete(url(shop, "/orders/" + withdrawn)).cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeDeletedAt").isNotEmpty())
                .andExpect(jsonPath("$.formStoreDeletedAt").isEmpty());
        mvc.perform(get(url(shop, "/orders/sent")).cookie(owner)).andExpect(jsonPath(withdrawnFilter).isEmpty());
        mvc.perform(get(url(supplier, "/orders/incoming")).cookie(owner))
                .andExpect(jsonPath(withdrawnFilter + ".storeDeletedAt").isNotEmpty());
        mvc.perform(post(url(supplier, "/orders/" + withdrawn + "/fulfill")).cookie(owner).with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(post(url(shop, "/orders/" + withdrawn + "/restore")).cookie(owner).with(csrf()))
                .andExpect(jsonPath("$.storeDeletedAt").isEmpty());
        mvc.perform(get(url(shop, "/orders/sent")).cookie(owner)).andExpect(jsonPath(withdrawnFilter).isNotEmpty());

        String rejected = id(send(owner, post(orderUrl), orderJson(today, kabanosItem, "1", pasztetItem, "1"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        String rejectedFilter = "$[?(@.id == '" + rejected + "')]";
        mvc.perform(delete(url(supplier, "/orders/" + rejected)).cookie(owner).with(csrf()))
                .andExpect(jsonPath("$.formStoreDeletedAt").isNotEmpty())
                .andExpect(jsonPath("$.storeDeletedAt").isEmpty());
        mvc.perform(get(url(supplier, "/orders/incoming")).cookie(owner)).andExpect(jsonPath(rejectedFilter).isEmpty());
        mvc.perform(get(url(shop, "/orders/sent")).cookie(owner))
                .andExpect(jsonPath(rejectedFilter + ".formStoreDeletedAt").isNotEmpty());
        mvc.perform(delete(url(shop, "/orders/" + orderId)).cookie(owner).with(csrf()))
                .andExpect(status().isConflict());

        // Zamówienie jedzie na dokumencie ruchu międzymagazynowego dostawcy: dostawca go przypina, zamawiający widzi.
        String movement = insertDocument(supplier, "MM", "MM0000120261022", java.time.LocalDate.now().atTime(11, 49));
        String otherType = insertDocument(supplier, "PL", "PL0000120261022", java.time.LocalDate.now().atTime(8, 0));
        String foreignMovement = insertDocument(shop, "MM", "MM0000920261022", java.time.LocalDate.now().atTime(9, 0));
        mvc.perform(get(url(supplier, "/orders/" + orderId + "/documents/candidates")).cookie(owner))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].number").value("MM0000120261022"))
                .andExpect(jsonPath("$[0].linked").value(false));
        String link = "{\"documentId\":\"" + movement + "\"}";
        send(owner, post(url(supplier, "/orders/" + orderId + "/documents")), link)
                .andExpect(jsonPath("$.documents.length()").value(1))
                .andExpect(jsonPath("$.documents[0].number").value("MM0000120261022"))
                .andExpect(jsonPath("$.documents[0].linkedBy").isNotEmpty());
        send(owner, post(url(supplier, "/orders/" + orderId + "/documents")), link).andExpect(status().isConflict());
        send(owner, post(url(supplier, "/orders/" + orderId + "/documents")), "{\"documentId\":\"" + otherType + "\"}")
                .andExpect(status().isNotFound());
        send(owner, post(url(supplier, "/orders/" + orderId + "/documents")), "{\"documentId\":\"" + foreignMovement + "\"}")
                .andExpect(status().isNotFound());
        // Zamawiający widzi dokument, ale nie przypina do zamówienia, którego nie realizuje.
        mvc.perform(get(url(shop, "/orders/" + orderId)).cookie(owner))
                .andExpect(jsonPath("$.documents[0].number").value("MM0000120261022"));
        send(owner, post(url(shop, "/orders/" + orderId + "/documents")), link).andExpect(status().isNotFound());
        // W module Dokumenty ruch międzymagazynowy pokazuje zamówienie, które na nim jedzie.
        mvc.perform(get("/api/stores/" + supplier + "/documents/mm/" + movement).cookie(owner))
                .andExpect(jsonPath("$.deliOrders[0].orderId").value(orderId));
        // Odpięcie zostawia wpis w historii, a ponowne przypięcie przywraca dokument.
        mvc.perform(delete(url(supplier, "/orders/" + orderId + "/documents/" + movement)).cookie(owner).with(csrf()))
                .andExpect(jsonPath("$.documents.length()").value(0));
        assertThat(jdbc.queryForObject("select count(*) from deli_order_documents where order_id = ?::uuid and unlinked_at is not null",
                Long.class, orderId)).isEqualTo(1);
        send(owner, post(url(supplier, "/orders/" + orderId + "/documents")), link)
                .andExpect(jsonPath("$.documents.length()").value(1));
        mvc.perform(get(url(supplier, "/orders/" + orderId + "/documents/candidates")).param("query", "mm00001").cookie(owner))
                .andExpect(jsonPath("$[0].linked").value(true));

        // Cofnięte zaproszenie odcina formularz, ale złożone zamówienie zostaje w historii.
        mvc.perform(delete(url(supplier, "/invitations/" + invitation)).cookie(owner).with(csrf()))
                .andExpect(jsonPath("$.length()").value(0));
        send(owner, post(orderUrl), orderJson(today, kabanosItem, "1", pasztetItem, "1")).andExpect(status().isNotFound());
        mvc.perform(get(url(shop, "/orders/" + orderId)).cookie(owner)).andExpect(status().isOk());

        // Sklep-dostawca zamawia ze swojego formularza bez zaproszenia.
        send(owner, post(url(supplier, "/suppliers/" + supplier + "/orders")), orderJson(today, kabanosItem, "3", pasztetItem, "1"))
                .andExpect(status().isCreated());
    }

    private static String orderJson(String deliveryDate, String firstItem, String firstQuantity, String secondItem,
            String secondQuantity) {
        return "{\"deliveryDate\":\"" + deliveryDate + "\",\"items\":[{\"itemId\":\"" + firstItem + "\",\"quantity\":"
                + firstQuantity + "},{\"itemId\":\"" + secondItem + "\",\"quantity\":" + secondQuantity + "}]}";
    }

    private ResultActions send(Cookie owner, MockHttpServletRequestBuilder request, String json) throws Exception {
        return mvc.perform(request.cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String url(String storeId, String path) {
        return "/api/stores/" + storeId + "/deli-order" + path;
    }

    private static String id(String json, String path) {
        return ((String) JsonPath.read(json, path));
    }

    private String createStore(Cookie owner) throws Exception {
        String number = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999_999L));
        String json = send(owner, post("/api/settings/stores"),
                "{\"number\":\"" + number + "\",\"name\":\"Sklep " + number + "\",\"active\":true}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return id(json, "$.id");
    }

    /** Produkt w kartotece sklepu, tak jakby przyszedł z importu raportu. */
    /** Dokument z raportu Wejścia/Wyjścia (bez linii) w sklepie - wystarczy do przypinania do zamówień. */
    private String insertDocument(String storeId, String type, String number, java.time.LocalDateTime date) {
        String importId = jdbc.queryForObject("""
                insert into document_imports (store_id, file_name, total_lines, saved_lines, created_count,
                    updated_count, skipped_lines)
                values (?::uuid, 'Zeszyt2.xlsx', 1, 1, 1, 0, 0) returning id""", String.class, storeId);
        return jdbc.queryForObject("""
                insert into documents (store_id, type, number, document_date, line_count, purchase_value,
                    sale_value_gross, first_import_id, last_import_id)
                values (?::uuid, ?, ?, ?, 0, 0, 0, ?::uuid, ?::uuid) returning id""",
                String.class, storeId, type, number, date, importId, importId);
    }

    private String ean(String productId) {
        return jdbc.queryForObject("select ean from products where id = ?::uuid", String.class, productId);
    }

    private String insertProduct(String storeId, String name) {
        return insertProduct(storeId, name, "kg");
    }

    private String insertProduct(String storeId, String name, String unit) {
        String importId = jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, total_rows, created_count, updated_count,
                    unchanged_count, missing_count)
                values (?::uuid, 'test.xlsx', 1, 1, 0, 0, 0) returning id""", String.class, storeId);
        String itm8 = "%013d".formatted(ThreadLocalRandom.current().nextLong(1, 9_999_999_999_999L));
        return jdbc.queryForObject("""
                insert into products (store_id, itm8, ean, name, unit, sale_price, active,
                    first_seen_import_id, last_seen_import_id)
                values (?::uuid, ?, ?, ?, ?, 9.99, true, ?::uuid, ?::uuid) returning id""",
                String.class, storeId, itm8, "59" + itm8.substring(2), name, unit, importId, importId);
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }
}
