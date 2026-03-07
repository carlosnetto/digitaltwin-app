package com.matera.digitaltwin.api.service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import com.matera.digitaltwin.api.client.MiniCoreClient;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates an account statement as a PDF and writes it directly to the provided
 * {@link OutputStream} — no temporary file is created at any point.
 *
 * The PDF is streamed to the HTTP response output stream, allowing the browser
 * (or mobile OS) to handle save, share, or print natively via its PDF viewer.
 */
@Service
public class StatementService {

    private static final Logger log = LoggerFactory.getLogger(StatementService.class);

    // ── Colours (Matera brand) ─────────────────────────────────────────────
    private static final Color NAVY       = new Color(0,   30,  96);   // #001E60
    private static final Color DARK_GRAY  = new Color(64,  64,  64);
    private static final Color ROW_ALT    = new Color(245, 247, 250);
    private static final Color DEBIT_RED  = new Color(200,  40,  40);
    private static final Color CREDIT_GRN = new Color(0,  130,  80);
    private static final Color BORDER     = new Color(220, 225, 235);

    private static final DateTimeFormatter DATE_FMT   = DateTimeFormatter.ofPattern("MMM dd, yyyy");
    private static final DateTimeFormatter TX_DT_FMT  = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm");
    private static final DateTimeFormatter TS_FMT     = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm z");

    private final JdbcTemplate              jdbc;
    private final MiniCoreClient            miniCoreClient;
    private final TransactionDisplayService displayService;

    public StatementService(JdbcTemplate jdbc,
                            MiniCoreClient miniCoreClient,
                            TransactionDisplayService displayService) {
        this.jdbc           = jdbc;
        this.miniCoreClient = miniCoreClient;
        this.displayService = displayService;
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Writes a statement PDF for the user's account in {@code currencyCode}
     * covering transactions with {@code effective_date} between {@code from} and
     * {@code to} (both inclusive) to {@code out}.
     *
     * @param email        authenticated user's email
     * @param currencyCode e.g. "USD", "BRL", "USDC"
     * @param from         start date (inclusive)
     * @param to           end date (inclusive)
     * @param lang         BCP-47 language tag for metadata summaries
     * @param out          response output stream — written directly, no buffering
     */
    public void generate(String email, String currencyCode,
                         LocalDate from, LocalDate to,
                         String lang, OutputStream out) throws Exception {

        // ── 1. Resolve user + account ──────────────────────────────────────
        Map<String, Object> accountInfo = resolveAccount(email, currencyCode);
        if (accountInfo == null) {
            throw new IllegalArgumentException(
                    "No account found for email=" + email + ", currency=" + currencyCode);
        }

        String userName      = (String) accountInfo.get("user_name");
        long   minicoreAccId = ((Number) accountInfo.get("minicore_account_id")).longValue();
        String currencyName  = (String) accountInfo.get("currency_name");
        int    decimalPlaces = ((Number) accountInfo.get("decimal_places")).intValue();

        // ── 2. Fetch + filter transactions ────────────────────────────────
        List<Map<String, Object>> all = miniCoreClient.getTransactions(minicoreAccId);

        List<Map<String, Object>> inRange = all.stream()
                .filter(tx -> {
                    String d = (String) tx.get("effective_date");
                    if (d == null || d.isBlank()) return false;
                    try {
                        LocalDate txDate = LocalDate.parse(d.substring(0, 10));
                        return !txDate.isBefore(from) && !txDate.isAfter(to);
                    } catch (Exception e) {
                        return false;
                    }
                })
                .sorted(Comparator.comparingLong(tx -> ((Number) tx.get("transaction_id")).longValue()))
                .toList();

        // ── 3. Resolve i18n summaries ──────────────────────────────────────
        Map<String, String> summaries = fetchSummaries(inRange, lang);

        // ── 4. Generate PDF ────────────────────────────────────────────────
        writePdf(out, userName, currencyCode, currencyName, decimalPlaces,
                from, to, inRange, summaries);
    }

    // ── PDF generation ─────────────────────────────────────────────────────

    private void writePdf(OutputStream out,
                          String userName, String currencyCode, String currencyName,
                          int decimalPlaces,
                          LocalDate from, LocalDate to,
                          List<Map<String, Object>> txs,
                          Map<String, String> summaries) throws Exception {

        Document doc = new Document(PageSize.A4, 40, 40, 55, 55);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        writer.setPageEvent(new PageNumbers());
        doc.open();

        // ── Fonts ─────────────────────────────────────────────────────────
        Font titleFnt = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, NAVY);
        Font labelFnt = FontFactory.getFont(FontFactory.HELVETICA_BOLD,  9, DARK_GRAY);
        Font valueFnt = FontFactory.getFont(FontFactory.HELVETICA,       9, DARK_GRAY);
        Font colFnt   = FontFactory.getFont(FontFactory.HELVETICA_BOLD,  8, Color.WHITE);
        Font cellFnt  = FontFactory.getFont(FontFactory.HELVETICA,       8, DARK_GRAY);
        Font totalFnt = FontFactory.getFont(FontFactory.HELVETICA_BOLD,  8, DARK_GRAY);

        // ── Logo + title (two-column header row) ──────────────────────────
        PdfPTable header = new PdfPTable(new float[]{60, 40});
        header.setWidthPercentage(100);
        header.setSpacingAfter(6);

        // Left: "Account Statement" title
        PdfPCell titleCell = new PdfPCell(new Phrase("Account Statement", titleFnt));
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setVerticalAlignment(Element.ALIGN_BOTTOM);
        titleCell.setPaddingBottom(2);
        header.addCell(titleCell);

        // Right: Matera logo (SVG transcoded to PNG in-memory)
        PdfPCell logoCell = new PdfPCell();
        logoCell.setBorder(Rectangle.NO_BORDER);
        logoCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        logoCell.setVerticalAlignment(Element.ALIGN_BOTTOM);
        byte[] logoPng = logoAsPng(20f);
        if (logoPng != null) {
            Image logo = Image.getInstance(logoPng);
            logo.setAlignment(Image.RIGHT);
            logoCell.addElement(logo);
        }
        header.addCell(logoCell);

        doc.add(header);

        // ── Info grid ─────────────────────────────────────────────────────
        PdfPTable info = new PdfPTable(2);
        info.setWidthPercentage(100);
        info.setSpacingBefore(10);
        info.setSpacingAfter(10);

        addInfoRow(info, "Account Holder", userName,                           labelFnt, valueFnt);
        addInfoRow(info, "Currency",       currencyCode + " — " + currencyName, labelFnt, valueFnt);
        addInfoRow(info, "Period",         from.format(DATE_FMT) + "  →  " + to.format(DATE_FMT), labelFnt, valueFnt);
        addInfoRow(info, "Generated",      ZonedDateTime.now().format(TS_FMT), labelFnt, valueFnt);
        doc.add(info);

        // ── Transaction table ──────────────────────────────────────────────
        if (txs.isEmpty()) {
            Font noTx = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 10, Color.GRAY);
            Paragraph none = new Paragraph("No transactions found for this period.", noTx);
            none.setSpacingBefore(20);
            none.setAlignment(Element.ALIGN_CENTER);
            doc.add(none);
            doc.close();
            return;
        }

        // Columns: Date+Time | Description | Debit | Credit | Balance
        PdfPTable table = new PdfPTable(new float[]{20, 40, 13, 13, 14});
        table.setWidthPercentage(100);
        table.setSpacingBefore(10);

        for (String col : new String[]{"Date", "Description", "Debit", "Credit", "Balance"}) {
            PdfPCell h = new PdfPCell(new Phrase(col, colFnt));
            h.setBackgroundColor(NAVY);
            h.setPadding(5);
            h.setBorder(Rectangle.NO_BORDER);
            h.setHorizontalAlignment(
                    col.equals("Date") || col.equals("Description")
                            ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
            table.addCell(h);
        }

        BigDecimal totalDebit  = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        boolean alt = false;

        for (Map<String, Object> tx : txs) {
            Color bg = alt ? ROW_ALT : Color.WHITE;
            alt = !alt;

            String ledgerId  = String.valueOf(((Number) tx.get("transaction_id")).longValue());
            String date      = safeDateTime((String) tx.get("created_at"));
            String desc      = summaries.getOrDefault(ledgerId,
                    capitalize((String) tx.getOrDefault("transaction_description", "")));
            boolean isCredit = "CREDIT".equals(tx.get("direction"));
            BigDecimal amt   = toBigDecimal(tx.get("amount"),                decimalPlaces);
            BigDecimal bal   = toBigDecimal(tx.get("post_available_balance"), decimalPlaces);

            if (isCredit) totalCredit = totalCredit.add(amt);
            else          totalDebit  = totalDebit.add(amt);

            Font debitFnt  = FontFactory.getFont(FontFactory.HELVETICA, 8, DEBIT_RED);
            Font creditFnt = FontFactory.getFont(FontFactory.HELVETICA, 8, CREDIT_GRN);

            table.addCell(cell(date,                                          bg, cellFnt,  Element.ALIGN_LEFT,  false));
            table.addCell(cell(desc,                                          bg, cellFnt,  Element.ALIGN_LEFT,  false));
            table.addCell(cell(isCredit ? "" : fmt(amt, decimalPlaces),       bg, debitFnt, Element.ALIGN_RIGHT, false));
            table.addCell(cell(isCredit ? fmt(amt, decimalPlaces) : "",       bg, creditFnt,Element.ALIGN_RIGHT, false));
            table.addCell(cell(fmt(bal, decimalPlaces),                       bg, cellFnt,  Element.ALIGN_RIGHT, false));
        }

        // Totals row
        Color totalBg = new Color(235, 238, 245);
        Font totalDebitFnt  = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, DEBIT_RED);
        Font totalCreditFnt = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, CREDIT_GRN);

        table.addCell(cell("",                            totalBg, totalFnt,       Element.ALIGN_LEFT,  true));
        table.addCell(cell("TOTAL",                       totalBg, totalFnt,       Element.ALIGN_LEFT,  true));
        table.addCell(cell(fmt(totalDebit,  decimalPlaces), totalBg, totalDebitFnt, Element.ALIGN_RIGHT, true));
        table.addCell(cell(fmt(totalCredit, decimalPlaces), totalBg, totalCreditFnt,Element.ALIGN_RIGHT, true));
        table.addCell(cell("",                            totalBg, totalFnt,       Element.ALIGN_RIGHT, true));

        doc.add(table);
        doc.close();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private Map<String, Object> resolveAccount(String email, String currencyCode) {
        try {
            return jdbc.queryForMap("""
                    SELECT u.name        AS user_name,
                           ua.minicore_account_id,
                           c.name        AS currency_name,
                           c.decimal_places
                    FROM digitaltwinapp.users u
                    JOIN digitaltwinapp.user_accounts ua ON ua.user_id = u.user_id
                    JOIN digitaltwinapp.currencies c     ON c.id = ua.currency_id
                    WHERE u.email = ? AND c.code = ?
                    """, email, currencyCode);
        } catch (Exception e) {
            log.warn("resolveAccount: no account for email={}, currency={}", email, currencyCode);
            return null;
        }
    }

    private Map<String, String> fetchSummaries(List<Map<String, Object>> txs, String lang) {
        if (txs.isEmpty()) return Map.of();

        List<String> ids = txs.stream()
                .map(tx -> String.valueOf(((Number) tx.get("transaction_id")).longValue()))
                .toList();

        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Map<String, String> result = new HashMap<>();

        try {
            jdbc.queryForList(
                    "SELECT trans_code, ledger_id, metadata::text AS metadata " +
                    "FROM digitaltwinapp.transaction_metadata " +
                    "WHERE ledger_id IN (" + placeholders + ")",
                    ids.toArray()
            ).forEach(row -> {
                try {
                    int transCode   = ((Number) row.get("trans_code")).intValue();
                    String ledgerId = (String) row.get("ledger_id");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metadata = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue((String) row.get("metadata"),
                                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
                    displayService.resolve(transCode, lang, metadata)
                            .ifPresent(d -> result.put(ledgerId, d.summary()));
                } catch (Exception e) {
                    log.debug("fetchSummaries: skipping row: {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("fetchSummaries: query failed: {}", e.getMessage());
        }

        return result;
    }

    private static void addInfoRow(PdfPTable table, String label, String value,
                                   Font labelFnt, Font valueFnt) {
        PdfPCell l = new PdfPCell(new Phrase(label, labelFnt));
        l.setBorder(Rectangle.BOTTOM);
        l.setBorderColor(BORDER);
        l.setPadding(4);
        table.addCell(l);

        PdfPCell v = new PdfPCell(new Phrase(value, valueFnt));
        v.setBorder(Rectangle.BOTTOM);
        v.setBorderColor(BORDER);
        v.setPadding(4);
        table.addCell(v);
    }

    private static PdfPCell cell(String text, Color bg, Font font, int align, boolean bold) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBackgroundColor(bg);
        c.setPaddingTop(4);
        c.setPaddingBottom(4);
        c.setPaddingLeft(5);
        c.setPaddingRight(5);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(BORDER);
        c.setHorizontalAlignment(align);
        return c;
    }

    private static String fmt(BigDecimal amount, int decimalPlaces) {
        return amount == null ? "" : amount.setScale(decimalPlaces, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal toBigDecimal(Object value, int decimalPlaces) {
        if (value == null) return BigDecimal.ZERO;
        try {
            return new BigDecimal(value.toString()).setScale(decimalPlaces, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static String safeDate(String raw) {
        if (raw == null || raw.length() < 10) return "";
        try {
            return LocalDate.parse(raw.substring(0, 10)).format(DATE_FMT);
        } catch (Exception e) {
            return raw;
        }
    }

    private static String safeDateTime(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            // mini-core returns ISO-8601: "2026-03-07T17:45:00" or with offset/Z
            return java.time.OffsetDateTime.parse(raw).format(TX_DT_FMT);
        } catch (Exception e) {
            try {
                return java.time.LocalDateTime.parse(raw).format(TX_DT_FMT);
            } catch (Exception e2) {
                return safeDate(raw);   // fall back to date-only if unparseable
            }
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    // ── Logo transcoding ───────────────────────────────────────────────────

    /**
     * Transcodes the classpath SVG logo to a PNG byte array at the requested height.
     * Batik renders the SVG paths entirely in-memory — no files, no network calls.
     *
     * @param heightPt desired rendered height in points (width scales proportionally from viewBox)
     * @return PNG bytes ready for {@link Image#getInstance(byte[])}, or {@code null} on failure
     */
    private byte[] logoAsPng(float heightPt) {
        try (InputStream svgStream = getClass().getResourceAsStream("/matera-logo.svg")) {
            if (svgStream == null) {
                log.warn("logoAsPng: matera-logo.svg not found on classpath");
                return null;
            }
            PNGTranscoder transcoder = new PNGTranscoder();
            transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, heightPt);
            ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
            transcoder.transcode(new TranscoderInput(svgStream), new TranscoderOutput(pngOut));
            return pngOut.toByteArray();
        } catch (Exception e) {
            log.warn("logoAsPng: transcoding failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Page numbers ───────────────────────────────────────────────────────

    private static class PageNumbers extends PdfPageEventHelper {
        private final Font font = FontFactory.getFont(FontFactory.HELVETICA, 7, Color.GRAY);

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfPTable footer = new PdfPTable(1);
            footer.setTotalWidth(document.getPageSize().getWidth() - 80);
            PdfPCell cell = new PdfPCell(new Phrase("Page " + writer.getPageNumber(), font));
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            footer.addCell(cell);
            footer.writeSelectedRows(0, -1, 40,
                    document.bottom() - 10, writer.getDirectContent());
        }
    }
}
