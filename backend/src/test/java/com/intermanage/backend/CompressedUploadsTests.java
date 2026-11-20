package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.common.component.CompressedUploads;
import com.intermanage.backend.common.exception.ApiException;

/** Pliki od agenta sklepu spakowane gzipem: rozpakowanie, nazwa bez .gz, limit rozmiaru, plik zwykły bez zmian. */
class CompressedUploadsTests {

    @Test
    void unpacksGzipAndDropsSuffix() throws IOException {
        byte[] text = "TRACEART linia\r\n".repeat(1000).getBytes(StandardCharsets.US_ASCII);
        MultipartFile packed = new MockMultipartFile("files", "TRACEART_20261120_101500_SR1_UT1.Traite0.gz",
                "application/octet-stream", gzip(text));
        MultipartFile unpacked = CompressedUploads.unpacked(packed, 1024 * 1024);
        assertThat(unpacked.getOriginalFilename()).isEqualTo("TRACEART_20261120_101500_SR1_UT1.Traite0");
        assertThat(unpacked.getBytes()).isEqualTo(text);
        assertThat(packed.getSize()).isLessThan(text.length / 10);
    }

    @Test
    void plainFilePassesUnchanged() {
        MultipartFile plain = new MockMultipartFile("file", "Histo.Log", "text/plain", "abc".getBytes());
        assertThat(CompressedUploads.unpacked(plain, 10)).isSameAs(plain);
    }

    @Test
    void rejectsFileTooLargeAfterUnpacking() throws IOException {
        MultipartFile bomb = new MockMultipartFile("file", "Histo.Log.gz", "application/octet-stream",
                gzip(new byte[2 * 1024 * 1024]));
        assertThatThrownBy(() -> CompressedUploads.unpacked(bomb, 1024 * 1024)).isInstanceOf(ApiException.class);
    }

    private static byte[] gzip(byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
            zip.write(content);
        }
        return out.toByteArray();
    }
}
