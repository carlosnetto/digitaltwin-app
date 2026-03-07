package com.matera.digitaltwin.api.service;

import com.lowagie.text.BaseColor;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import com.matera.digitaltwin.api.client.MiniCoreClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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
    private static final BaseColor NAVY       = new BaseColor(0,   30,  96);   // #001E60
    private static final BaseColor ROW_ALT    = new BaseColor(245, 247, 250);
    private static final BaseColor DEBIT_RED  = new BaseColor(200,  40,  40);
    private static final BaseColor CREDIT_GRN = new BaseColor(0,  130,  80);
    private static final BaseColor BORDER     = new BaseColor(220, 225, 235);

    private static final DateTimeFormatter DATE_FMT  = DateTimeFormatter.ofPattern("MMM dd, yyyy");
    private static final DateTimeFormatter TS_FMT    = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm z");

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

        String userName       = (String) accountInfo.get("user_name");
        long   minicoreAccId  = ((Number) accountInfo.get("minicore_account_id")).longValue();
        String currencyName   = (String) accountInfo.get("currency_name");
        int    decimalPlaces  = ((Number) accountInfo.get("decimal_places")).intValue();

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

        // ── Header ────────────────────────────────────────────────────────
        Font appFont  = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, NAVY);
        Font titleFnt = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, NAVY);
        Font labelFnt = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, new BaseColor(64, 64, 64));
        Font valueFnt = FontFactory.getFont(FontFactory.HELVETICA, 9, new BaseColor(64, 64, 64));
        Font colFnt   = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, BaseColor.WHITE);
        Font cellFnt  = FontFactory.getFont(FontFactory.HELVETICA, 8, new BaseColor(64, 64, 64));
        Font amtFnt   = FontFactory.getFont(FontFactory.HELVETICA, 8, new BaseColor(64, 64, 64));
        Font totalFnt = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, new BaseColor(64, 64, 64));

        Paragraph appName = new Paragraph("Digital Twin — Matera", appFont);
        appName.setAlignment(Element.ALIGN_RIGHT);
        doc.add(appName);

        Paragraph title = new Paragraph("Account Statement", titleFnt);
        title.setSpacingBefore(4);
        doc.add(title);

        // Divider
        doc.add(divider());

        // Info table (2 columns)
        PdfPTable info = new PdfPTable(2);
        info.setWidthPercentage(100);
        info.setSpacingBefore(8);
        info.setSpacingAfter(8);

        addInfoRow(info, "Account Holder", userName, labelFnt, valueFnt);
        addInfoRow(info, "Currency", currencyCode + " — " + currencyName, labelFnt, valueFnt);
        addInfoRow(info, "Period",
                from.format(DATE_FMT) + "  →  " + to.format(DATE_FMT), labelFnt, valueFnt);
        addInfoRow(info, "Generated",
                ZonedDateTime.now().format(TS_FMT), labelFnt, valueFnt);

        doc.add(info);
        doc.add(divider());

        // ── Transaction table ──────────────────────────────────────────────
        if (txs.isEmpty()) {
            Font noTx = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 10, BaseColor.GRAY);
            Paragraph none = new Paragraph("No transactions found for this period.", noTx);
            none.setSpacingBefore(20);
            none.setAlignment(Element.ALIGN_CENTER);
            doc.add(none);
            doc.close();
            return;
        }

        // Columns: Date | Description | Debit | Credit | Balance
        PdfPTable table = new PdfPTable(new float[]{14, 46, 13, 13, 14});
        table.setWidthPercentage(100);
        table.setSpacingBefore(10);

        for (String header : new String[]{"Date", "Description", "Debit", "Credit", "Balance"}) {
            PdfPCell h = new PdfPCell(new Phrase(header, colFnt));
            h.setBackgroundColor(NAVY);
            h.setPadding(5);
            h.setBorder(Rectangle.NO_BORDER);
            h.setHorizontalAlignment(header.equals("Description") ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
            if (header.equals("Date") || header.equals("Description")) {
                h.setHorizontalAlignment(Element.ALIGN_LEFT);
            }
            table.addCell(h);
        }

        BigDecimal totalDebit  = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        boolean alt = false;

        for (Map<String, Object> tx : txs) {
            BaseColor bg = alt ? ROW_ALT : BaseColor.WHITE;
            alt = !alt;

            String ledgerId  = String.valueOf(((Number) tx.get("transaction_id")).longValue());
            String date      = safeDate((String) tx.get("effective_date"));
            String desc      = summaries.getOrDefault(ledgerId,
                    capitalize((String) tx.getOrDefault("transaction_description", "")));
            boolean isCredit = "CREDIT".equals(tx.get("direction"));
            BigDecimal amt   = toBigDecimal(tx.get("amount"), decimalPlaces);
            BigDecimal bal   = toBigDecimal(tx.get("post_available_balance"), decimalPlaces);

            if (isCredit) totalCredit = totalCredit.add(amt);
            else          totalDebit  = totalDebit.add(amt);

            table.addCell(cell(date,              bg, cellFnt, Element.ALIGN_LEFT,  false));
            table.addCell(cell(desc,              bg, cellFnt, Element.ALIGN_LEFT,  false));
            table.addCell(cell(isCredit ? "" : fmt(amt, decimalPlaces), bg,
                    amountFont(false, false, amtFnt), Element.ALIGN_RIGHT, false));
            table.addCell(cell(isCredit ? fmt(amt, decimalPlaces) : "", bg,
                    amountFont(true,  false, amtFnt), Element.ALIGN_RIGHT, false));
            table.addCell(cell(fmt(bal, decimalPlaces), bg, amtFnt, Element.ALIGN_RIGHT, false));
        }

        // Totals row
        BaseColor totalBg = new BaseColor(235, 238, 245);
        table.addCell(cell("", totalBg, totalFnt, Element.ALIGN_LEFT, true));
        table.addCell(cell("TOTAL", totalBg, totalFnt, Element.ALIGN_LEFT, true));
        table.addCell(cell(fmt(totalDebit,  decimalPlaces), totalBg,
                amountFont(false, true, totalFnt), Element.ALIGN_RIGHT, true));
        table.addCell(cell(fmt(totalCredit, decimalPlaces), totalBg,
                amountFont(true,  true, totalFnt), Element.ALIGN_RIGHT, true));
        table.addCell(cell("", totalBg, totalFnt, Element.ALIGN_RIGHT, true));

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

    private static Paragraph divider() {
        Paragraph p = new Paragraph(" ");
        p.setSpacingBefore(2);
        p.setSpacingAfter(2);
        // Simulate a thin line via underline on an empty string — OpenPDF uses a separator chunk
        return p;
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

    private static PdfPCell cell(String text, BaseColor bg, Font font,
                                 int align, boolean bold) {
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

    private static Font amountFont(boolean isCredit, boolean bold, Font base) {
        int style = bold ? Font.BOLD : Font.NORMAL;
        return FontFactory.getFont(FontFactory.HELVETICA,
                base.getSize(), style,
                isCredit ? CREDIT_GRN : DEBIT_RED);
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

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    // ── Page numbers ───────────────────────────────────────────────────────

    private static class PageNumbers extends PdfPageEventHelper {
        private final Font font = FontFactory.getFont(FontFactory.HELVETICA, 7, BaseColor.GRAY);

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfPTable footer = new PdfPTable(1);
            footer.setTotalWidth(document.getPageSize().getWidth() - 80);
            PdfPCell cell = new PdfPCell(
                    new Phrase("Page " + writer.getPageNumber(), font));
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            footer.addCell(cell);
            footer.writeSelectedRows(0, -1, 40,
                    document.bottom() - 10, writer.getDirectContent());
        }
    }
}
