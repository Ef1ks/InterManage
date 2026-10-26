package com.intermanage.backend.component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import org.springframework.stereotype.Component;

import com.lowagie.text.pdf.BaseFont;

/** Fonty DejaVu (z polskimi znakami) wbudowywane w PDF-y aplikacji; wczytywane raz przy starcie. */
@Component
public class PdfFonts {

    private final BaseFont regular = load("fonts/DejaVuSans.ttf");
    private final BaseFont bold = load("fonts/DejaVuSans-Bold.ttf");

    public BaseFont regular() {
        return regular;
    }

    public BaseFont bold() {
        return bold;
    }

    private static BaseFont load(String resource) {
        try (InputStream in = PdfFonts.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Brak fontu " + resource);
            }
            byte[] bytes = in.readAllBytes();
            return BaseFont.createFont(resource.substring(resource.lastIndexOf('/') + 1), BaseFont.IDENTITY_H,
                    BaseFont.EMBEDDED, true, bytes, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
