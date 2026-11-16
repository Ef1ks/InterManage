package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.download.component.WindowsShortcut;

/** Folder docelowy ze skrótów zrobionych w Windows (WScript.Shell), w {@code resources/lnk}. */
class WindowsShortcutTests {

    @Test
    void localFolderWithPolishLetters() throws IOException {
        assertThat(WindowsShortcut.target(read("local-folder.lnk"))).contains("F:\\IMTest\\Raporty Żółte");
    }

    @Test
    void networkShare() throws IOException {
        assertThat(WindowsShortcut.target(read("network-folder.lnk"))).contains("\\\\LOCALHOST\\C$\\Windows");
    }

    /** Dysku N: nie było na komputerze, więc skrót ma ścieżkę tylko w liście elementów powłoki. */
    @Test
    void driveMissingOnThisComputer() throws IOException {
        assertThat(WindowsShortcut.target(read("missing-drive.lnk"))).contains("N:\\AVISOCONFIG\\BACKUP");
    }

    @Test
    void otherFilesAreNotShortcuts() {
        assertThat(WindowsShortcut.isShortcut("MZ to jest program".getBytes())).isFalse();
        assertThat(WindowsShortcut.target(new byte[] {1, 2, 3})).isEmpty();
    }

    static byte[] read(String name) throws IOException {
        try (InputStream in = WindowsShortcutTests.class.getResourceAsStream("/lnk/" + name)) {
            return in.readAllBytes();
        }
    }
}
