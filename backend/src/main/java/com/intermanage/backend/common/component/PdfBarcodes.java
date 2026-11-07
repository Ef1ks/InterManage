package com.intermanage.backend.common.component;

import com.lowagie.text.Image;
import com.lowagie.text.pdf.Barcode;
import com.lowagie.text.pdf.Barcode128;
import com.lowagie.text.pdf.BarcodeEAN;
import com.lowagie.text.pdf.PdfContentByte;

/** Kody kreskowe do skanowania z wydruków PDF (zamówienie garmażu, formularz kart produkcji). */
public final class PdfBarcodes {

    private PdfBarcodes() {
    }

    /**
     * EAN-13 / EAN-8, gdy kod ma poprawną cyfrę kontrolną - tak jak na opakowaniu. Kody wewnętrzne sklepu
     * z błędną sumą kontrolną drukujemy jako Code 128, który czytnik odczyta dokładnie w tej postaci.
     */
    public static Image image(PdfContentByte canvas, String code, float maxWidth, float maxHeight) {
        return image(canvas, code, 0.85f, maxWidth, maxHeight);
    }

    /** Jak {@link #image(PdfContentByte, String, float, float)}, z szerokością modułu (najwęższej kreski) w punktach. */
    public static Image image(PdfContentByte canvas, String code, float moduleWidth, float maxWidth, float maxHeight) {
        return create(canvas, code, moduleWidth, maxWidth, maxHeight, true);
    }

    /** Same kreski, bez cyfr: wydruk podpisuje kod sam, gdy liczba pod nim ma być inna niż treść kodu. */
    public static Image bars(PdfContentByte canvas, String code, float maxWidth, float maxHeight) {
        return create(canvas, code, 0.85f, maxWidth, maxHeight, false);
    }

    private static Image create(PdfContentByte canvas, String code, float moduleWidth, float maxWidth, float maxHeight,
            boolean digits) {
        Barcode barcode;
        if (isValidEan(code)) {
            BarcodeEAN ean = new BarcodeEAN();
            ean.setCodeType(code.length() == 13 ? Barcode.EAN13 : Barcode.EAN8);
            barcode = ean;
        } else {
            barcode = new Barcode128();
            barcode.setCodeType(Barcode.CODE128);
        }
        barcode.setCode(code);
        barcode.setBarHeight(22);
        barcode.setX(moduleWidth);
        barcode.setSize(7);
        barcode.setBaseline(7);
        if (!digits) {
            barcode.setFont(null);
        }
        Image image = barcode.createImageWithBarcode(canvas, null, null);
        image.scaleToFit(maxWidth, maxHeight);
        return image;
    }

    public static boolean isValidEan(String code) {
        if (code == null || !(code.length() == 13 || code.length() == 8) || !code.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < code.length() - 1; i++) {
            int digit = code.charAt(code.length() - 2 - i) - '0';
            sum += i % 2 == 0 ? digit * 3 : digit;
        }
        return (10 - sum % 10) % 10 == code.charAt(code.length() - 1) - '0';
    }
}
