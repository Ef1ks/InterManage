package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.intermanage.backend.auth.security.JwtService;
import com.intermanage.backend.common.component.ObjectStorage;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** Zdjęcie profilowe w R2 (podmienionym): plik idzie do bucketu, w bazie zostaje sam klucz, adres jest z CDN. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.ksef.sync-enabled=false"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class UserAvatarIntegrationTests {

    private static final String URL = "/api/auth/avatar";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 4, 5, 6};

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    ObjectStorage storage;

    @BeforeEach
    void storage() {
        reset(storage);
        when(storage.enabled()).thenReturn(true);
        when(storage.publicUrl(anyString())).thenAnswer(call -> "https://cdn.test/" + call.getArgument(0));
    }

    @Test
    void avatarGoesToR2AndCanBeRestored() throws Exception {
        Cookie owner = login();
        assertThat(JsonPath.<Object>read(me(owner), "$.avatar")).isNull();

        mvc.perform(upload(owner, "skan.pdf", new byte[] {0x25, 0x50, 0x44, 0x46, 1}))
                .andExpect(status().isBadRequest());

        String first = mvc.perform(upload(owner, "ja.jpg", JPEG)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String firstId = JsonPath.read(first, "$.avatar.id");
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).put(key.capture(), eq("image/jpeg"), eq(JPEG));
        assertThat(key.getValue()).matches("avatars/[0-9a-f-]{36}/[0-9a-f-]{36}\\.jpg");
        assertThat(JsonPath.<String>read(first, "$.avatar.url")).isEqualTo("https://cdn.test/" + key.getValue());
        assertThat(JsonPath.<String>read(me(owner), "$.avatar.url")).isEqualTo("https://cdn.test/" + key.getValue());
        // W bazie tylko klucz, bez treści; backend pliku z R2 nie serwuje.
        assertThat(jdbc.queryForObject("select content is null from user_avatars where id = ?::uuid",
                Boolean.class, firstId)).isTrue();
        mvc.perform(get(URL + "/" + firstId).cookie(owner)).andExpect(status().isNotFound());

        // Błąd R2 nie zmienia obowiązującego zdjęcia.
        doThrow(new ObjectStorage.ObjectStorageException("brak", null)).when(storage)
                .put(anyString(), anyString(), any());
        mvc.perform(upload(owner, "inne.jpg", JPEG)).andExpect(status().isServiceUnavailable());
        assertThat(JsonPath.<String>read(me(owner), "$.avatar.id")).isEqualTo(firstId);

        String removed = mvc.perform(delete(URL).cookie(owner).with(csrf())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(removed, "$.previousId")).isEqualTo(firstId);
        assertThat(JsonPath.<Object>read(me(owner), "$.avatar")).isNull();

        mvc.perform(post(URL + "/" + firstId + "/restore").cookie(owner).with(csrf())).andExpect(status().isOk());
        assertThat(JsonPath.<String>read(me(owner), "$.avatar.id")).isEqualTo(firstId);
    }

    private org.springframework.test.web.servlet.RequestBuilder upload(Cookie cookie, String name, byte[] content) {
        return multipart(HttpMethod.PUT, URL).file(new MockMultipartFile("file", name, null, content))
                .cookie(cookie).with(csrf());
    }

    private String me(Cookie cookie) throws Exception {
        return mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }
}
