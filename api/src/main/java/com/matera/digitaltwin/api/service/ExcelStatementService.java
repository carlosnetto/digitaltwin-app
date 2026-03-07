package com.matera.digitaltwin.api.service;

import com.matera.digitaltwin.api.client.MiniCoreClient;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.awt.Color;
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
 * Generates an account statement as an XLSX workbook and writes it directly
 * to the provided {@link OutputStream} — no temporary file is created.
 */
@Service
public class ExcelStatementService {

    private static final Logger log = LoggerFactory.getLogger(ExcelStatementService.class);

    // ── Colours (Matera brand) ─────────────────────────────────────────────
    private static final XSSFColor NAVY        = xssfColor(0,   30,  96);
    private static final XSSFColor DARK_GRAY   = xssfColor(64,  64,  64);
    private static final XSSFColor ROW_ALT     = xssfColor(245, 247, 250);
    private static final XSSFColor DEBIT_RED   = xssfColor(200,  40,  40);
    private static final XSSFColor CREDIT_GRN  = xssfColor(0,  130,  80);
    private static final XSSFColor TOTAL_BG    = xssfColor(235, 238, 245);
    private static final XSSFColor WHITE       = xssfColor(255, 255, 255);

    private static final DateTimeFormatter DATE_FMT  = DateTimeFormatter.ofPattern("MMM dd, yyyy");
    private static final DateTimeFormatter TX_DT_FMT = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm");
    private static final DateTimeFormatter TS_FMT    = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm z");

    private final JdbcTemplate              jdbc;
    private final MiniCoreClient            miniCoreClient;
    private final TransactionDisplayService displayService;

    public ExcelStatementService(JdbcTemplate jdbc,
                                 MiniCoreClient miniCoreClient,
                                 TransactionDisplayService displayService) {
        this.jdbc           = jdbc;
        this.miniCoreClient = miniCoreClient;
        this.displayService = displayService;
    }

    // ── Public API ─────────────────────────────────────────────────────────

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

        // ── 4. Generate XLSX ────────────────────────────────────────────────
        writeXlsx(out, userName, currencyCode, currencyName, decimalPlaces,
                from, to, inRange, summaries);
    }

    // ── XLSX generation ────────────────────────────────────────────────────

    private void writeXlsx(OutputStream out,
                            String userName, String currencyCode, String currencyName,
                            int decimalPlaces,
                            LocalDate from, LocalDate to,
                            List<Map<String, Object>> txs,
                            Map<String, String> summaries) throws Exception {

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("Statement");

            // ── Column widths ────────────────────────────────────────────
            sheet.setColumnWidth(0, 28 * 256);  // Date+Time
            sheet.setColumnWidth(1, 50 * 256);  // Description
            sheet.setColumnWidth(2, 16 * 256);  // Debit
            sheet.setColumnWidth(3, 16 * 256);  // Credit
            sheet.setColumnWidth(4, 18 * 256);  // Balance

            // ── Pre-build styles ─────────────────────────────────────────
            XSSFCellStyle logoInfoStyle  = infoLabelStyle(wb);
            XSSFCellStyle infoLabelStyle = infoLabelStyle(wb);
            XSSFCellStyle infoValueStyle = infoValueStyle(wb);
            XSSFCellStyle colHeaderStyle = colHeaderStyle(wb);
            XSSFCellStyle colHeaderRightStyle = colHeaderRightStyle(wb);
            XSSFCellStyle cellStyle      = dataStyle(wb, WHITE,    DARK_GRAY, false, false);
            XSSFCellStyle cellAltStyle   = dataStyle(wb, ROW_ALT,  DARK_GRAY, false, false);
            XSSFCellStyle cellRightStyle      = dataRightStyle(wb, WHITE,    DARK_GRAY, false);
            XSSFCellStyle cellAltRightStyle   = dataRightStyle(wb, ROW_ALT,  DARK_GRAY, false);
            XSSFCellStyle debitStyle     = dataRightStyle(wb, WHITE,    DEBIT_RED,  false);
            XSSFCellStyle debitAltStyle  = dataRightStyle(wb, ROW_ALT,  DEBIT_RED,  false);
            XSSFCellStyle creditStyle    = dataRightStyle(wb, WHITE,    CREDIT_GRN, false);
            XSSFCellStyle creditAltStyle = dataRightStyle(wb, ROW_ALT,  CREDIT_GRN, false);
            XSSFCellStyle totalLabelStyle  = dataStyle(wb, TOTAL_BG, DARK_GRAY, true, false);
            XSSFCellStyle totalDebitStyle  = dataRightStyle(wb, TOTAL_BG, DEBIT_RED,  true);
            XSSFCellStyle totalCreditStyle = dataRightStyle(wb, TOTAL_BG, CREDIT_GRN, true);
            XSSFCellStyle totalBlankStyle  = dataRightStyle(wb, TOTAL_BG, DARK_GRAY,  true);

            int rowNum = 0;

            // ── Info rows ────────────────────────────────────────────────
            rowNum = addInfoRow(sheet, rowNum, "Account Statement", "",         infoLabelStyle, infoValueStyle, true);
            rowNum = addInfoRow(sheet, rowNum, "Account Holder",    userName,   infoLabelStyle, infoValueStyle, false);
            rowNum = addInfoRow(sheet, rowNum, "Currency",
                    currencyCode + " — " + currencyName,           infoLabelStyle, infoValueStyle, false);
            rowNum = addInfoRow(sheet, rowNum, "Period",
                    from.format(DATE_FMT) + "  →  " + to.format(DATE_FMT),    infoLabelStyle, infoValueStyle, false);
            rowNum = addInfoRow(sheet, rowNum, "Generated",
                    ZonedDateTime.now().format(TS_FMT),             infoLabelStyle, infoValueStyle, false);
            rowNum++; // blank separator

            // ── Column headers ───────────────────────────────────────────
            XSSFRow headerRow = sheet.createRow(rowNum++);
            headerRow.setHeightInPoints(18);
            String[] cols = {"Date", "Description", "Debit", "Credit", "Balance"};
            for (int i = 0; i < cols.length; i++) {
                XSSFCell c = headerRow.createCell(i);
                c.setCellValue(cols[i]);
                c.setCellStyle(i >= 2 ? colHeaderRightStyle : colHeaderStyle);
            }

            // ── Transaction rows ─────────────────────────────────────────
            if (txs.isEmpty()) {
                XSSFRow emptyRow = sheet.createRow(rowNum);
                XSSFCell ec = emptyRow.createCell(0);
                ec.setCellValue("No transactions found for this period.");
                ec.setCellStyle(infoValueStyle);
                sheet.addMergedRegion(new CellRangeAddress(rowNum, rowNum, 0, 4));
            } else {
                BigDecimal totalDebit  = BigDecimal.ZERO;
                BigDecimal totalCredit = BigDecimal.ZERO;
                boolean alt = false;

                for (Map<String, Object> tx : txs) {
                    String ledgerId  = String.valueOf(((Number) tx.get("transaction_id")).longValue());
                    String date      = safeDateTime((String) tx.get("created_at"));
                    String desc      = summaries.getOrDefault(ledgerId,
                            capitalize((String) tx.getOrDefault("transaction_description", "")));
                    boolean isCredit = "CREDIT".equals(tx.get("direction"));
                    BigDecimal amt   = toBigDecimal(tx.get("amount"),                decimalPlaces);
                    BigDecimal bal   = toBigDecimal(tx.get("post_available_balance"), decimalPlaces);

                    if (isCredit) totalCredit = totalCredit.add(amt);
                    else          totalDebit  = totalDebit.add(amt);

                    XSSFRow row = sheet.createRow(rowNum++);
                    row.setHeightInPoints(15);

                    XSSFCellStyle leftStyle  = alt ? cellAltStyle   : cellStyle;
                    XSSFCellStyle rightStyle = alt ? cellAltRightStyle : cellRightStyle;
                    XSSFCellStyle dbtStyle   = alt ? debitAltStyle  : debitStyle;
                    XSSFCellStyle crdStyle   = alt ? creditAltStyle : creditStyle;

                    setCell(row, 0, date,                                           leftStyle);
                    setCell(row, 1, desc,                                           leftStyle);
                    setCell(row, 2, isCredit ? "" : fmt(amt, decimalPlaces),        dbtStyle);
                    setCell(row, 3, isCredit ? fmt(amt, decimalPlaces) : "",        crdStyle);
                    setCell(row, 4, fmt(bal, decimalPlaces),                        rightStyle);

                    alt = !alt;
                }

                // Totals row
                XSSFRow totRow = sheet.createRow(rowNum);
                totRow.setHeightInPoints(16);
                setCell(totRow, 0, "",                              totalBlankStyle);
                setCell(totRow, 1, "TOTAL",                         totalLabelStyle);
                setCell(totRow, 2, fmt(totalDebit,  decimalPlaces), totalDebitStyle);
                setCell(totRow, 3, fmt(totalCredit, decimalPlaces), totalCreditStyle);
                setCell(totRow, 4, "",                              totalBlankStyle);
            }

            wb.write(out);
        }
    }

    // ── Style factories ────────────────────────────────────────────────────

    private static XSSFCellStyle infoLabelStyle(XSSFWorkbook wb) {
        XSSFCellStyle s = wb.createCellStyle();
        XSSFFont f = wb.createFont();
        f.setBold(true);
        f.setFontHeightInPoints((short) 10);
        applyFontColor(f, DARK_GRAY);
        s.setFont(f);
        s.setFillForegroundColor(WHITE);
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        return s;
    }

    private static XSSFCellStyle infoValueStyle(XSSFWorkbook wb) {
        XSSFCellStyle s = wb.createCellStyle();
        XSSFFont f = wb.createFont();
        f.setFontHeightInPoints((short) 10);
        applyFontColor(f, DARK_GRAY);
        s.setFont(f);
        s.setFillForegroundColor(WHITE);
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        return s;
    }

    private static XSSFCellStyle colHeaderStyle(XSSFWorkbook wb) {
        XSSFCellStyle s = wb.createCellStyle();
        XSSFFont f = wb.createFont();
        f.setBold(true);
        f.setFontHeightInPoints((short) 9);
        applyFontColor(f, WHITE);
        s.setFont(f);
        s.setFillForegroundColor(NAVY);
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.LEFT);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        return s;
    }

    private static XSSFCellStyle colHeaderRightStyle(XSSFWorkbook wb) {
        XSSFCellStyle s = colHeaderStyle(wb);
        s.setAlignment(HorizontalAlignment.RIGHT);
        return s;
    }

    private static XSSFCellStyle dataStyle(XSSFWorkbook wb, XSSFColor bg,
                                            XSSFColor fg, boolean bold, boolean right) {
        XSSFCellStyle s = wb.createCellStyle();
        XSSFFont f = wb.createFont();
        f.setBold(bold);
        f.setFontHeightInPoints((short) 9);
        applyFontColor(f, fg);
        s.setFont(f);
        s.setFillForegroundColor(bg);
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(right ? HorizontalAlignment.RIGHT : HorizontalAlignment.LEFT);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setWrapText(false);
        return s;
    }

    private static XSSFCellStyle dataRightStyle(XSSFWorkbook wb, XSSFColor bg,
                                                 XSSFColor fg, boolean bold) {
        return dataStyle(wb, bg, fg, bold, true);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static void applyFontColor(XSSFFont font, XSSFColor color) {
        font.setColor(color);
    }

    private static XSSFColor xssfColor(int r, int g, int b) {
        return new XSSFColor(new Color(r, g, b), null);
    }

    private static void setCell(XSSFRow row, int col, String value, XSSFCellStyle style) {
        XSSFCell c = row.createCell(col);
        c.setCellValue(value);
        c.setCellStyle(style);
    }

    private static int addInfoRow(XSSFSheet sheet, int rowNum,
                                   String label, String value,
                                   XSSFCellStyle labelStyle, XSSFCellStyle valueStyle,
                                   boolean titleRow) {
        XSSFRow row = sheet.createRow(rowNum);
        row.setHeightInPoints(titleRow ? 22 : 16);
        XSSFCell lc = row.createCell(0);
        lc.setCellValue(label);
        if (titleRow) {
            XSSFWorkbook wb = sheet.getWorkbook();
            XSSFCellStyle titleStyle = wb.createCellStyle();
            XSSFFont tf = wb.createFont();
            tf.setBold(true);
            tf.setFontHeightInPoints((short) 16);
            applyFontColor(tf, NAVY);
            titleStyle.setFont(tf);
            titleStyle.setFillForegroundColor(WHITE);
            titleStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            lc.setCellStyle(titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(rowNum, rowNum, 0, 4));
        } else {
            lc.setCellStyle(labelStyle);
            XSSFCell vc = row.createCell(1);
            vc.setCellValue(value);
            vc.setCellStyle(valueStyle);
        }
        return rowNum + 1;
    }

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

    private static String safeDateTime(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            return java.time.OffsetDateTime.parse(raw).format(TX_DT_FMT);
        } catch (Exception e) {
            try {
                return java.time.LocalDateTime.parse(raw).format(TX_DT_FMT);
            } catch (Exception e2) {
                return raw.length() >= 10 ? raw.substring(0, 10) : raw;
            }
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}
