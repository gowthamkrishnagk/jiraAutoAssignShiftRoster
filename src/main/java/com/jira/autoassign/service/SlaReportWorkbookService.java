package com.jira.autoassign.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.*;

/**
 * Builds the .xlsx that rides along with the daily report email — the same sheet the
 * "Download SLA Report" button produces in the SLA Tracker, for the reported day.
 *
 * Workbook layout:
 *   1. "Breach Reason Pivot"     — Team | Assignee | Email | Open | Resolved | Total (reason still empty)
 *   2. "&lt;team&gt; Open"       — every OPEN breached ticket for that team
 *   3. "&lt;team&gt; Resolved"   — every breached ticket resolved on the reported day
 *
 * Ticket columns and header styling deliberately mirror the frontend export
 * (SLA_EXPORT_COLS / styleSheet in index.html) so both downloads look identical.
 */
@Service
public class SlaReportWorkbookService {

    private static final Logger log = LoggerFactory.getLogger(SlaReportWorkbookService.class);

    /** Must stay in sync with SLA_EXPORT_COLS in index.html. */
    private static final String[] TICKET_COLS = {
        "Assignee", "Email", "Ticket", "Summary", "Ticket Status", "Severity",
        "SLA Status", "Remaining / Completed At", "Breach Time", "Reassigned To", "Breach Reason"
    };
    private static final int[] TICKET_WIDTHS = { 20, 28, 14, 50, 16, 12, 22, 20, 20, 20, 30 };

    private static final String[] PIVOT_COLS   = { "Team", "Assignee", "Email", "Open", "Resolved", "Total" };
    private static final int[]    PIVOT_WIDTHS = { 24, 26, 30, 10, 12, 10 };

    /**
     * @param report  the built report — carries each team's grouped open/resolved tickets
     * @param reasons ticket key → logged breach reason (absent key = reason still empty)
     * @return xlsx bytes, or null if the workbook could not be written
     */
    public byte[] build(SlaDailyReportService.Report report, Map<String, String> reasons) {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            CellStyle header = headerStyle(wb);
            CellStyle body   = bodyStyle(wb);
            Set<String> usedNames = new HashSet<>();

            // ---- 1. Pivot sheet ----
            Sheet pivot = wb.createSheet(uniqueName("Breach Reason Pivot", usedNames));
            writeHeader(pivot, PIVOT_COLS, PIVOT_WIDTHS, header);
            int r = 1;
            for (SlaDailyReportService.TeamPivot tp : report.teams()) {
                for (SlaDailyReportService.PivotRow row : tp.rows()) {
                    Row xr = pivot.createRow(r++);
                    cell(xr, 0, tp.teamName(), body);
                    cell(xr, 1, row.name(),    body);
                    cell(xr, 2, row.email(),   body);
                    num (xr, 3, row.open(),     body);
                    num (xr, 4, row.resolved(), body);
                    num (xr, 5, row.total(),    body);
                }
            }
            // Grand total row
            Row totalRow = pivot.createRow(r);
            cell(totalRow, 0, "Total", header);
            cell(totalRow, 1, "", header);
            cell(totalRow, 2, "", header);
            num (totalRow, 3, report.teams().stream().mapToInt(SlaDailyReportService.TeamPivot::open).sum(),     header);
            num (totalRow, 4, report.teams().stream().mapToInt(SlaDailyReportService.TeamPivot::resolved).sum(), header);
            num (totalRow, 5, report.grandTotal(), header);
            pivot.createFreezePane(0, 1);

            // ---- 2 & 3. Per-team ticket sheets ----
            for (SlaDailyReportService.TeamPivot tp : report.teams()) {
                if (tp.error() != null) continue;
                addTicketSheet(wb, uniqueName(tp.teamName() + " Open", usedNames),
                               tp.openGroups(), reasons, header, body);
                addTicketSheet(wb, uniqueName(tp.teamName() + " Res " + report.resolvedDate(), usedNames),
                               tp.resolvedGroups(), reasons, header, body);
            }

            wb.write(out);
            return out.toByteArray();

        } catch (Exception e) {
            log.error("[SLA-Report] Could not build xlsx attachment: {}", e.getMessage(), e);
            return null;
        }
    }

    /** Flattens one assignee-grouped list into a styled ticket sheet. */
    @SuppressWarnings("unchecked")
    private void addTicketSheet(Workbook wb, String name, List<Map<String, Object>> groups,
                                Map<String, String> reasons, CellStyle header, CellStyle body) {

        Sheet sheet = wb.createSheet(name);
        writeHeader(sheet, TICKET_COLS, TICKET_WIDTHS, header);

        int r = 1;
        for (Map<String, Object> g : (groups == null ? List.<Map<String, Object>>of() : groups)) {
            String assignee = str(g.get("name"));
            String email    = str(g.get("email"));
            if ("__unassigned__".equals(email)) email = "";

            for (Map<String, Object> t : (List<Map<String, Object>>) g.getOrDefault("tickets", List.of())) {
                Map<String, Object> sla = (Map<String, Object>) t.get("sla");
                String remaining  = sla == null ? "" : str(sla.get("remaining"));
                String completed  = sla == null ? "" : str(sla.get("completedAt"));
                String timeCol    = !remaining.isBlank() ? remaining : completed;
                String issueKey   = str(t.get("key"));

                Row xr = sheet.createRow(r++);
                cell(xr,  0, assignee,                    body);
                cell(xr,  1, email,                       body);
                cell(xr,  2, issueKey,                    body);
                cell(xr,  3, str(t.get("summary")),       body);
                cell(xr,  4, str(t.get("status")),        body);
                cell(xr,  5, str(t.get("severity")),      body);
                cell(xr,  6, slaStatusLabel(sla),         body);
                cell(xr,  7, timeCol,                     body);
                cell(xr,  8, sla == null ? "" : str(sla.get("breachTime")), body);
                cell(xr,  9, str(t.get("reassignedTo")),  body);
                cell(xr, 10, reasons.getOrDefault(issueKey, ""), body);
            }
        }
        sheet.createFreezePane(0, 1);
    }

    /** Mirrors slaStatusLabel() in index.html so both exports read the same. */
    private static String slaStatusLabel(Map<String, Object> sla) {
        if (sla == null || !Boolean.TRUE.equals(sla.get("available"))) return "N/A";
        String status = str(sla.get("status"));
        return switch (status) {
            case "breached"           -> "Breached (Open)";
            case "completed_breached" -> "Breached (Resolved)";
            case "paused"             -> "Paused";
            case "ongoing"            -> "Ongoing";
            case "completed"          -> "Met";
            default                   -> status.isBlank() ? "N/A" : status;
        };
    }

    // -----------------------------------------------------------------------
    // Sheet helpers
    // -----------------------------------------------------------------------

    private static void writeHeader(Sheet sheet, String[] cols, int[] widths, CellStyle style) {
        for (int c = 0; c < cols.length; c++)
            sheet.setColumnWidth(c, widths[c] * 256);
        Row row = sheet.createRow(0);
        for (int c = 0; c < cols.length; c++) cell(row, c, cols[c], style);
    }

    private static void cell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value == null ? "" : value);
        c.setCellStyle(style);
    }

    private static void num(Row row, int col, int value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value);
        c.setCellStyle(style);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** Excel sheet names cap at 31 chars, forbid : \ / ? * [ ] and must be unique. */
    private static String uniqueName(String raw, Set<String> used) {
        String base = raw.replaceAll("[\\\\/?*\\[\\]:]", "-").trim();
        if (base.isBlank()) base = "Sheet";
        if (base.length() > 31) base = base.substring(0, 31);
        String name = base;
        int n = 2;
        while (used.contains(name.toLowerCase())) {
            String suffix = " (" + n++ + ")";
            String head   = base.length() + suffix.length() > 31
                          ? base.substring(0, 31 - suffix.length()) : base;
            name = head + suffix;
        }
        used.add(name.toLowerCase());
        return name;
    }

    // -----------------------------------------------------------------------
    // Styles — created once per workbook (Excel caps total cell styles)
    // -----------------------------------------------------------------------

    private static CellStyle headerStyle(XSSFWorkbook wb) {
        Font f = wb.createFont();
        f.setBold(true);
        f.setFontHeightInPoints((short) 11);
        f.setColor(IndexedColors.WHITE.getIndex());

        CellStyle s = wb.createCellStyle();
        s.setFont(f);
        s.setFillForegroundColor(new XSSFColor(new byte[]{ 0x1E, 0x3A, 0x5F }, null));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        border(s);
        return s;
    }

    private static CellStyle bodyStyle(XSSFWorkbook wb) {
        Font f = wb.createFont();
        f.setFontHeightInPoints((short) 10);

        CellStyle s = wb.createCellStyle();
        s.setFont(f);
        s.setWrapText(true);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        border(s);
        return s;
    }

    private static void border(CellStyle s) {
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        short grey = IndexedColors.GREY_40_PERCENT.getIndex();
        s.setTopBorderColor(grey);
        s.setBottomBorderColor(grey);
        s.setLeftBorderColor(grey);
        s.setRightBorderColor(grey);
    }
}
