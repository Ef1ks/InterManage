package com.intermanage.backend.bakery.component;

import java.util.List;

import org.springframework.stereotype.Component;

/** Odczyt skanów WZ jeszcze niepodłączony; {@link BakeryScanReader} mówi, jak go podłączyć. */
@Component
public class NoBakeryScanReader implements BakeryScanReader {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public Reading read(byte[] content, String contentType, List<String> articles) {
        throw new Unavailable("odczyt skanów WZ nie jest jeszcze podłączony");
    }
}
