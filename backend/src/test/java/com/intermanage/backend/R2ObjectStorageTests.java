package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.common.component.R2ObjectStorage;

/** Podpis AWS Signature V4 na przykładzie z dokumentacji S3 („GET Object”, examplebucket). */
class R2ObjectStorageTests {

    @Test
    void signsLikeAwsExample() {
        String emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String authorization = R2ObjectStorage.authorization("GET", "/test.txt", "",
                Map.of("host", "examplebucket.s3.amazonaws.com", "range", "bytes=0-9",
                        "x-amz-content-sha256", emptyHash, "x-amz-date", "20130524T000000Z"),
                emptyHash, "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1",
                "20130524T000000Z");
        assertThat(authorization).isEqualTo("AWS4-HMAC-SHA256 "
                + "Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, "
                + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
    }

    @Test
    void publicUrlGetsHttps() {
        R2ObjectStorage storage = new R2ObjectStorage("https://konto.r2.cloudflarestorage.com/bucket/", "id",
                "sekret", "cdn.intermanage.pl/");
        assertThat(storage.enabled()).isTrue();
        assertThat(storage.publicUrl("avatars/a.jpg")).isEqualTo("https://cdn.intermanage.pl/avatars/a.jpg");
        assertThat(new R2ObjectStorage("", "", "", "").enabled()).isFalse();
    }
}
