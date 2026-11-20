package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.intermanage.backend.agent.component.AgentPresenceJob;
import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.Cookie;

/**
 * Agent sklepu: klucz, czekanie na pliki (long polling), przydział pliku przed zapisem (bez podwójnego zapisu),
 * zdarzenia z folderów i wiadomości na żywo dla przeglądarek.
 */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.auth.token-refresh-after=0s"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class StoreAgentIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AgentPresenceJob presence;

    @Test
    void keyWorksUntilAgentIsRemoved() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String body = mvc.perform(post("/api/stores/" + storeId + "/agents").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Komputer biuro\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.agents[0].key").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String key = JsonPath.read(body, "$.agents[0].key");
        String agentId = JsonPath.read(body, "$.agents[0].id");
        // Klucz jest pokazywany tylko raz.
        mvc.perform(get("/api/stores/" + storeId + "/agents").cookie(owner))
                .andExpect(jsonPath("$.agents[0].key").doesNotExist());

        hello(key).andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(storeId))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.folders['deli-order-proto']").value("N:\\AVISOCONFIG\\BACKUP"))
                .andExpect(jsonPath("$.watches[0].kind").value("TRACEART"))
                .andExpect(jsonPath("$.longPollSeconds").value(25));
        hello("ima_zly-klucz").andExpect(status().isForbidden());
        mvc.perform(post("/api/store-agent/hello").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/stores/" + storeId + "/agents").cookie(owner))
                .andExpect(jsonPath("$.agents[0].online").value(true))
                .andExpect(jsonPath("$.agents[0].machineName").value("KASA-1"));
        // Wybór sklepu: dopiero włączona praca przez agenta czyni go aktywnym dla przeglądarek.
        mvc.perform(get("/api/stores/" + storeId + "/agents/status").cookie(owner))
                .andExpect(jsonPath("$.online").value(true))
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(put("/api/stores/" + storeId + "/agents/settings").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(get("/api/stores/" + storeId + "/agents/status").cookie(owner))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.version").value("1.0.0"));

        mvc.perform(delete("/api/stores/" + storeId + "/agents/" + agentId).cookie(owner).with(csrf()))
                .andExpect(status().isOk());
        hello(key).andExpect(status().isForbidden());
        mvc.perform(post("/api/stores/" + storeId + "/agents/" + agentId + "/restore").cookie(owner).with(csrf()))
                .andExpect(status().isOk());
        hello(key).andExpect(status().isOk());
    }

    @Test
    void waitingAgentGetsFileAddedMeanwhileAndTimesOutEmpty() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String key = addAgent(owner, storeId, "Biuro");

        // Sklep pracuje przez przeglądarkę: agent nie bierze plików z kolejki.
        String early = enqueue(owner, storeId, "PROTO9_20261120_101400_10023_jan.Traite0");
        MvcResult disabled = wait(key);
        assertThatThrownBy(() -> disabled.getAsyncResult(300)).isInstanceOf(IllegalStateException.class);
        timeOut(disabled);
        enable(owner, storeId);
        assertThat(wait(key).getAsyncResult(10_000).toString()).contains(early);

        MvcResult waiting = mvc.perform(get("/api/store-agent/file-queue/wait?timeout=25").header(
                        HttpHeaders.AUTHORIZATION, "Bearer " + key))
                .andExpect(request().asyncStarted()).andReturn();
        String fileId = enqueue(owner, storeId, "PROTO1_20261120_101500_10023_jan.Traite0");
        Object result = waiting.getAsyncResult(10_000);
        assertThat(result.toString()).contains(fileId).contains("PROTO1_20261120_101500_10023_jan.Traite0");
        mvc.perform(asyncDispatch(waiting)).andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].id").value(fileId))
                .andExpect(jsonPath("$.files[0].folderKey").value("deli-order-proto"));

        // Bez plików: czas mija, pusta lista.
        MvcResult empty = mvc.perform(get("/api/store-agent/file-queue/wait?timeout=1").header(
                        HttpHeaders.AUTHORIZATION, "Bearer " + key))
                .andExpect(request().asyncStarted()).andReturn();
        timeOut(empty);
        mvc.perform(asyncDispatch(empty)).andExpect(status().isOk())
                .andExpect(jsonPath("$.files.length()").value(0));
    }

    @Test
    void fileIsClaimedByOneAndSavedOnlyByHolder() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String first = addAgent(owner, storeId, "Biuro");
        String second = addAgent(owner, storeId, "Zaplecze");
        enable(owner, storeId);
        String fileId = enqueue(owner, storeId, "CTRLPRIX_20261120_101500_GOBARTO_jan.traite0");

        MvcResult won = wait(first);
        assertThat(won.getAsyncResult(10_000).toString()).contains(fileId);
        // Drugi agent nic nie dostaje, a przeglądarka nie przydzieli sobie pliku, który zapisuje agent.
        MvcResult lost = wait(second);
        assertThatThrownBy(() -> lost.getAsyncResult(300)).isInstanceOf(IllegalStateException.class);
        timeOut(lost);
        mvc.perform(post(queue(storeId, fileId) + "/claim").cookie(owner).with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/store-agent/file-queue/" + fileId + "/saved")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + second))
                .andExpect(status().isConflict());
        mvc.perform(post(queue(storeId, fileId) + "/saved").cookie(owner).with(csrf()))
                .andExpect(status().isConflict());

        // Wygasły przydział wraca: plik można przydzielić od nowa.
        jdbc.update("update store_file_queue set claim_expires_at = now() - interval '1 second' where id = ?::uuid",
                fileId);
        mvc.perform(post(queue(storeId, fileId) + "/claim").cookie(owner).with(csrf()))
                .andExpect(status().isOk());
        // Teraz agent, który go miał, nie potwierdzi zapisu.
        mvc.perform(post("/api/store-agent/file-queue/" + fileId + "/saved")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + first))
                .andExpect(status().isConflict());
        mvc.perform(post(queue(storeId, fileId) + "/saved").cookie(owner).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get(queue(storeId, fileId)).cookie(owner))
                .andExpect(jsonPath("$.state").value("SAVED"))
                .andExpect(jsonPath("$.savedByAgent").value(false));

        // Zapis przez agenta i nieudany zapis: plik po błędzie nie wraca sam do agenta, zapisuje go przeglądarka.
        String saved = enqueue(owner, storeId, "PROTO2_20261120_101600_10023_jan.Traite0");
        assertThat(wait(first).getAsyncResult(10_000).toString()).contains(saved);
        mvc.perform(get("/api/store-agent/file-queue/" + saved + "/file")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + first))
                .andExpect(status().isOk());
        mvc.perform(post("/api/store-agent/file-queue/" + saved + "/saved")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + first))
                .andExpect(status().isNoContent());
        mvc.perform(get(queue(storeId, saved)).cookie(owner))
                .andExpect(jsonPath("$.state").value("SAVED"))
                .andExpect(jsonPath("$.savedByAgent").value(true));

        String failed = enqueue(owner, storeId, "PROTO3_20261120_101700_10023_jan.Traite0");
        assertThat(wait(first).getAsyncResult(10_000).toString()).contains(failed);
        mvc.perform(post("/api/store-agent/file-queue/" + failed + "/failed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + first)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"error\":\"Plik już jest w folderze\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get(queue(storeId, failed)).cookie(owner))
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.error").value("Plik już jest w folderze"));
        MvcResult again = wait(first);
        assertThatThrownBy(() -> again.getAsyncResult(300)).isInstanceOf(IllegalStateException.class);
        timeOut(again);
        mvc.perform(post(queue(storeId, failed) + "/claim").cookie(owner).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void folderEventsGoToPriceControlTraceartAndAthos() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String key = addAgent(owner, storeId, "Biuro");
        String disposition = mvc.perform(post("/api/stores/" + storeId + "/data-entry/export/price-control")
                        .cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"ean\":\"5904634010327\",\"salePrice\":4.99}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        String fileName = disposition.replaceAll(".*filename=\"([^\"]+)\".*", "$1");
        String loaded = fileName.replaceAll("\\.traite0$", ".Traite1");

        MvcResult stream = mvc.perform(get("/api/stores/" + storeId + "/live").cookie(owner))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(post("/api/store-agent/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"events":[
                                  {"kind":"RENAMED","folderKey":"CTRLPRIX","fileName":"%s"},
                                  {"kind":"CREATED","folderKey":"TRACEART",
                                   "fileName":"TRACEART_20261120_101500_SR1_UT1.Traite0"},
                                  {"kind":"PRESENT","folderKey":"TRACEART","fileName":"TRACEART_20251120_101500.Traite0"},
                                  {"kind":"CHANGED","folderKey":"ATHOS","fileName":"Histo.Log",
                                   "modified":"2026-11-20T09:00:00Z"}
                                ]}""".formatted(loaded)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests.length()").value(2))
                .andExpect(jsonPath("$.requests[0].folderKey").value("TRACEART"))
                .andExpect(jsonPath("$.requests[0].fileName").value("TRACEART_20261120_101500_SR1_UT1.Traite0"))
                .andExpect(jsonPath("$.requests[1].folderKey").value("ATHOS"));
        mvc.perform(get("/api/stores/" + storeId + "/data-entry/price-control-files").cookie(owner))
                .andExpect(jsonPath("$.pending.length()").value(0))
                .andExpect(jsonPath("$.loaded[0].processedCount").value(1));
        awaitContent(stream, "\"topic\":\"price-control\"");
        assertThat(stream.getResponse().getContentAsString()).contains("\"action\":\"loaded\"");

        // Wolne łącze: końcówka dziennika ATHOS spakowana gzipem. Bez wysyłek to nie błąd, a odczyt zapamiętuje datę
        // pliku, więc to samo zdarzenie nie prosi już o plik.
        java.io.ByteArrayOutputStream packed = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream zip = new java.util.zip.GZIPOutputStream(packed)) {
            zip.write("linia bez wysylek\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        mvc.perform(multipart("/api/store-agent/athos-history")
                        .file(new MockMultipartFile("file", "Histo.Log.gz", "application/octet-stream",
                                packed.toByteArray()))
                        .param("modified", String.valueOf(Instant.parse("2026-11-20T09:00:00Z").toEpochMilli()))
                        .param("partial", "true")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true))
                .andExpect(jsonPath("$.fileName").value("Histo.Log"));
        mvc.perform(post("/api/store-agent/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"events":[{"kind":"CHANGED","folderKey":"ATHOS","fileName":"Histo.Log",
                                  "modified":"2026-11-20T09:00:00Z"}]}"""))
                .andExpect(jsonPath("$.requests.length()").value(0));
    }

    @Test
    void liveStreamReachesOnlyItsStoreAndDoesNotRefreshToken() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String otherStoreId = createStore(owner);

        // Zwykłe żądanie odnawia token (w teście od razu), strumień na żywo nie.
        assertThat(mvc.perform(get("/api/auth/me").cookie(owner)).andReturn().getResponse()
                .getCookie(JwtService.COOKIE_NAME)).isNotNull();
        MvcResult stream = mvc.perform(get("/api/stores/" + storeId + "/live").cookie(owner))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(stream.getResponse().getCookie(JwtService.COOKIE_NAME)).isNull();
        assertThat(stream.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        MvcResult other = mvc.perform(get("/api/stores/" + otherStoreId + "/live").cookie(owner))
                .andExpect(request().asyncStarted()).andReturn();

        String fileId = enqueue(owner, storeId, "PROTO4_20261120_101800_10023_jan.Traite0");
        awaitContent(stream, fileId);
        assertThat(stream.getResponse().getContentAsString()).contains("\"topic\":\"file-queue\"")
                .contains("\"action\":\"added\"");
        assertThat(other.getResponse().getContentAsString()).doesNotContain("file-queue");

        // Bez dostępu do sklepu nie ma strumienia.
        mvc.perform(get("/api/stores/" + storeId + "/live")).andExpect(status().isUnauthorized());
        presence.check();
    }

    @Test
    void foldersHaveAppDefaultsAndOwnPathOnlyForItsStore() throws Exception {
        Cookie owner = login();
        String first = createStore(owner);
        String second = createStore(owner);
        // Bez wpisu: domyślne ścieżki aplikacji, także folder ATHOS pod kluczem z id sklepu.
        mvc.perform(get("/api/stores/" + second + "/file-locations").cookie(owner))
                .andExpect(jsonPath("$.locations['folder:data-entry-xml']").value("H:\\GCOM\\Aviso\\BLdirect"))
                .andExpect(jsonPath("$.locations['folder:mea-comrnis:" + second + "']").value("N:\\comrnis"));
        // Własna ścieżka tylko w pierwszym sklepie; ścieżka równa domyślnej to domyślna.
        mvc.perform(put("/api/stores/" + first + "/file-locations").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"locations":{"folder:data-entry-xml":"P:\\\\XML",
                                  "folder:data-entry-lst":"n:\\\\aloa\\\\lst\\\\import\\\\"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locations['folder:data-entry-xml']").value("P:\\XML"));
        mvc.perform(get("/api/stores/" + second + "/file-locations").cookie(owner))
                .andExpect(jsonPath("$.locations['folder:data-entry-xml']").value("H:\\GCOM\\Aviso\\BLdirect"));
        mvc.perform(get("/api/stores/" + first + "/file-locations/folders").cookie(owner))
                .andExpect(jsonPath("$[?(@.key == 'data-entry-xml')].custom").value(true))
                .andExpect(jsonPath("$[?(@.key == 'data-entry-xml')].path").value("P:\\XML"))
                .andExpect(jsonPath("$[?(@.key == 'data-entry-lst')].custom").value(false))
                .andExpect(jsonPath("$[?(@.key == 'deli-order-proto')].path").value("N:\\AVISOCONFIG\\BACKUP"));
        String key = addAgent(owner, first, "Biuro");
        hello(key).andExpect(jsonPath("$.folders['data-entry-xml']").value("P:\\XML"))
                .andExpect(jsonPath("$.folders['data-entry-product-file']").value("H:\\GCOM\\Aviso\\BLdirect\\CAD_BEZP"))
                .andExpect(jsonPath("$.watches[?(@.kind == 'ATHOS')].path").value("N:\\ATHOS2000"));
        // Przywrócenie domyślnej: pusta ścieżka.
        mvc.perform(put("/api/stores/" + first + "/file-locations").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"locations\":{\"folder:data-entry-xml\":\"\"}}"))
                .andExpect(jsonPath("$.locations['folder:data-entry-xml']").value("H:\\GCOM\\Aviso\\BLdirect"));
        // Folder plików PROTO: własny tylko w sklepie.
        mvc.perform(put("/api/stores/" + first + "/proto-settings").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderPath\":\"N:\\\\PROTO\",\"hiddenProtocols\":[]}"))
                .andExpect(jsonPath("$.folderPath").value("N:\\PROTO"));
        mvc.perform(get("/api/stores/" + second + "/proto-settings").cookie(owner))
                .andExpect(jsonPath("$.folderPath").value("N:\\AVISOCONFIG\\BACKUP"))
                .andExpect(jsonPath("$.defaultFolder").value(true));
    }


    private void awaitContent(MvcResult stream, String text) throws Exception {
        Instant deadline = Instant.now().plusSeconds(10);
        while (!stream.getResponse().getContentAsString().contains(text) && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
        }
        assertThat(stream.getResponse().getContentAsString()).contains(text);
    }

    private static void timeOut(MvcResult result) throws IOException {
        MockAsyncContext context = (MockAsyncContext) result.getRequest().getAsyncContext();
        for (AsyncListener listener : List.copyOf(context.getListeners())) {
            listener.onTimeout(new AsyncEvent(context));
        }
    }

    private MvcResult wait(String key) throws Exception {
        return mvc.perform(get("/api/store-agent/file-queue/wait?timeout=25")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key))
                .andExpect(request().asyncStarted()).andReturn();
    }

    private void enable(Cookie owner, String storeId) throws Exception {
        mvc.perform(put("/api/stores/" + storeId + "/agents/settings").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk());
    }

    private static String queue(String storeId, String fileId) {
        return "/api/stores/" + storeId + "/file-queue/" + fileId;
    }

    private org.springframework.test.web.servlet.ResultActions hello(String key) throws Exception {
        return mvc.perform(post("/api/store-agent/hello").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":\"1.0.0\",\"machineName\":\"KASA-1\",\"user\":\"sklep\"}"));
    }

    private String addAgent(Cookie owner, String storeId, String name) throws Exception {
        String body = mvc.perform(post("/api/stores/" + storeId + "/agents").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> keys = JsonPath.read(body, "$.agents[?(@.key)].key");
        return keys.getFirst();
    }

    private String enqueue(Cookie owner, String storeId, String fileName) throws Exception {
        String body = mvc.perform(multipart("/api/stores/" + storeId + "/file-queue").cookie(owner).with(csrf())
                        .file(new MockMultipartFile("file", fileName, MediaType.APPLICATION_OCTET_STREAM_VALUE,
                                "5900000000001000001000\r\n".getBytes(StandardCharsets.UTF_8)))
                        .param("folderKey", "deli-order-proto")
                        .param("folderLabel", "Protokół")
                        .param("fileName", fileName))
                .andExpect(status().isOk())
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

    private String createStore(Cookie cookie) throws Exception {
        String number = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        String body = mvc.perform(post("/api/settings/stores").cookie(cookie).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep agenta\",\"active\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }
}
