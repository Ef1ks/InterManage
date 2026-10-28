package com.intermanage.backend.mail.component;

import java.util.ArrayList;
import java.util.List;

/**
 * Treść maila składana z bloków (nagłówek, akapit, przycisk, dane, przypis) we wspólnym układzie. Z tych samych
 * bloków powstaje wersja HTML i tekstowa, więc nowy rodzaj maila to tylko kolejność bloków.
 */
public final class MailContent {

    private sealed interface Block {
    }

    private record Heading(String text) implements Block {
    }

    private record Paragraph(String text) implements Block {
    }

    private record Button(String label, String url) implements Block {
    }

    private record Details(List<Detail> rows) implements Block {
    }

    private record Note(String text) implements Block {
    }

    /** Wiersz bloku danych: etykieta i wartość. */
    public record Detail(String label, String value) {
    }

    private static final String FOOTER = "Wiadomość wysłana automatycznie z aplikacji Intermanage.";

    private final List<Block> blocks = new ArrayList<>();

    public MailContent heading(String text) {
        blocks.add(new Heading(text));
        return this;
    }

    public MailContent paragraph(String text) {
        blocks.add(new Paragraph(text));
        return this;
    }

    /** Główna akcja maila. W wersji tekstowej i pod przyciskiem jest też sam adres, gdyby przycisk nie działał. */
    public MailContent button(String label, String url) {
        blocks.add(new Button(label, url));
        return this;
    }

    public MailContent details(Detail... rows) {
        blocks.add(new Details(List.of(rows)));
        return this;
    }

    public MailContent note(String text) {
        blocks.add(new Note(text));
        return this;
    }

    public String text() {
        StringBuilder out = new StringBuilder();
        for (Block block : blocks) {
            switch (block) {
                case Heading h -> out.append(h.text()).append("\n\n");
                case Paragraph p -> out.append(p.text()).append("\n\n");
                case Button b -> out.append(b.label()).append(":\n").append(b.url()).append("\n\n");
                case Details d -> {
                    d.rows().forEach(r -> out.append(r.label()).append(": ").append(r.value()).append('\n'));
                    out.append('\n');
                }
                case Note n -> out.append(n.text()).append("\n\n");
            }
        }
        return out.append("--\n").append(FOOTER).append('\n').toString();
    }

    public String html(String subject) {
        StringBuilder body = new StringBuilder();
        for (Block block : blocks) {
            switch (block) {
                case Heading h -> body.append("""
                        <h1 style="margin:0 0 16px;font-size:20px;line-height:28px;font-weight:600;color:#18181b">%s</h1>
                        """.formatted(escape(h.text())));
                case Paragraph p -> body.append("""
                        <p style="margin:0 0 16px;font-size:15px;line-height:24px;color:#3f3f46">%s</p>
                        """.formatted(escape(p.text())));
                case Button b -> body.append("""
                        <table role="presentation" cellpadding="0" cellspacing="0" style="margin:8px 0 12px"><tr>
                        <td style="border-radius:8px;background:#18181b">
                        <a href="%1$s" style="display:inline-block;padding:12px 24px;font-size:15px;font-weight:600;color:#ffffff;text-decoration:none">%2$s</a>
                        </td></tr></table>
                        <p style="margin:0 0 20px;font-size:12px;line-height:18px;color:#71717a">Jeśli przycisk nie działa, skopiuj ten adres do przeglądarki:<br>
                        <a href="%1$s" style="color:#3f3f46;word-break:break-all">%1$s</a></p>
                        """.formatted(escape(b.url()), escape(b.label())));
                case Details d -> {
                    body.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" "
                            + "style=\"margin:0 0 20px;font-size:14px;line-height:22px\">");
                    for (Detail r : d.rows()) {
                        body.append("""
                                <tr><td style="padding:2px 16px 2px 0;color:#71717a">%s</td>\
                                <td style="padding:2px 0;color:#18181b;font-weight:600">%s</td></tr>
                                """.formatted(escape(r.label()), escape(r.value())));
                    }
                    body.append("</table>");
                }
                case Note n -> body.append("""
                        <p style="margin:0 0 16px;font-size:13px;line-height:20px;color:#71717a">%s</p>
                        """.formatted(escape(n.text())));
            }
        }
        return """
                <!doctype html>
                <html lang="pl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
                <title>%s</title></head>
                <body style="margin:0;padding:0;background:#f4f4f5;font-family:-apple-system,'Segoe UI',Roboto,Arial,sans-serif">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f4f4f5"><tr><td align="center" style="padding:32px 16px">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:560px">
                <tr><td style="padding:0 0 16px;font-size:16px;font-weight:700;color:#e2001a">Intermanage</td></tr>
                <tr><td style="background:#ffffff;border:1px solid #e4e4e7;border-radius:12px;padding:32px">
                %s</td></tr>
                <tr><td style="padding:16px 4px 0;font-size:12px;line-height:18px;color:#a1a1aa">%s</td></tr>
                </table></td></tr></table>
                </body></html>
                """.formatted(escape(subject), body, escape(FOOTER));
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
