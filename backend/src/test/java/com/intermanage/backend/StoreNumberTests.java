package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.store.entity.Store;

/** Raporty z systemu sklepu podają numer bez zera wiodącego (9575 dla sklepu 09575). */
class StoreNumberTests {

    @Test
    void leadingZerosDoNotMatter() {
        assertThat(Store.sameNumber("09575", "9575")).isTrue();
        assertThat(Store.sameNumber("9575", "09575")).isTrue();
        assertThat(Store.sameNumber("10023", "10023")).isTrue();
        assertThat(Store.sameNumber("10023", " 10023 ")).isTrue();
    }

    @Test
    void otherStoreOrMissingNumberDoesNotMatch() {
        assertThat(Store.sameNumber("09575", "95750")).isFalse();
        assertThat(Store.sameNumber("10023", "10025")).isFalse();
        assertThat(Store.sameNumber("10023", null)).isFalse();
        assertThat(Store.sameNumber("10023", "")).isFalse();
    }
}
