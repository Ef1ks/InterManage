package com.intermanage.backend.common.component;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;

/**
 * Stopka wydruków PDF: opis (dokument, sklep, data wydruku) po lewej, "Strona X z Y" po prawej. Liczba stron jest
 * dopisywana do szablonu dopiero po zamknięciu dokumentu.
 */
public final class PdfPageFooter extends PdfPageEventHelper {

    private static final float TOTAL_WIDTH = 24;

    private final Font font;
    private final String text;
    private PdfTemplate total;
    private int pages;

    public PdfPageFooter(Font font, String text) {
        this.font = font;
        this.text = text;
    }

    @Override
    public void onOpenDocument(PdfWriter writer, Document document) {
        total = writer.getDirectContent().createTemplate(TOTAL_WIDTH, 12);
    }

    @Override
    public void onEndPage(PdfWriter writer, Document document) {
        pages = writer.getPageNumber();
        PdfContentByte canvas = writer.getDirectContent();
        float y = document.bottom() - 22;
        ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT, new Phrase(text, font), document.left(), y, 0);
        float right = document.right() - TOTAL_WIDTH;
        ColumnText.showTextAligned(canvas, Element.ALIGN_RIGHT, new Phrase("Strona " + pages + " z", font),
                right, y, 0);
        canvas.addTemplate(total, right + 3, y - 3);
    }

    @Override
    public void onCloseDocument(PdfWriter writer, Document document) {
        ColumnText.showTextAligned(total, Element.ALIGN_LEFT, new Phrase(String.valueOf(pages), font), 0, 3, 0);
    }
}
