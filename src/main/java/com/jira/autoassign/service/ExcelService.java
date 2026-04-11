package com.jira.autoassign.service;

import com.jira.autoassign.entity.ShiftRoster;
import com.jira.autoassign.repository.ShiftRosterRepository;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ExcelService {

    private static final Logger log = LoggerFactory.getLogger(ExcelService.class);

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("d/M/yyyy"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        DateTimeFormatter.ofPattern("M/d/yyyy"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        DateTimeFormatter.ofPattern("dd.MM.yyyy")
    );

    private final ShiftRosterRepository rosterRepo;

    public ExcelService(ShiftRosterRepository rosterRepo) {
        this.rosterRepo = rosterRepo;
    }

    // -----------------------------------------------------------------------
    // Parsed raw row (one Excel row, before date expansion)
    // -----------------------------------------------------------------------
    public record RawRow(
        String email,
        LocalTime shiftStart,
        LocalTime shiftEnd,
        LocalDate fromDate,
        LocalDate toDate,
        int daysCount   // toDate - fromDate + 1
    ) {}

    public record ParseResult(List<RawRow> rows, List<String> warnings) {
        int totalEntries() { return rows.stream().mapToInt(RawRow::daysCount).sum(); }
    }

    // -----------------------------------------------------------------------
    // PREVIEW — parse Excel, return rows for review, nothing saved to DB
    // -----------------------------------------------------------------------
    public Map<String, Object> previewExcel(MultipartFile file) throws IOException {
        ParseResult result = parseFile(file);

        List<Map<String, Object>> rows = result.rows().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("email",      r.email());
            m.put("shiftStart", r.shiftStart().toString());
            m.put("shiftEnd",   r.shiftEnd().toString());
            m.put("fromDate",   r.fromDate().toString());
            m.put("toDate",     r.toDate().toString());
            m.put("daysCount",  r.daysCount());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success",      true);
        resp.put("rowCount",     result.rows().size());
        resp.put("totalEntries", result.totalEntries());
        resp.put("rows",         rows);
        resp.put("warnings",     result.warnings());
        return resp;
    }

    // -----------------------------------------------------------------------
    // SAVE — parse, expand date ranges, persist (scoped to a team)
    // -----------------------------------------------------------------------
    @Transactional
    public Map<String, Object> processExcel(MultipartFile file, String teamId) throws IOException {
        ParseResult result = parseFile(file);

        // Expand each raw row into one ShiftRoster entry per day
        List<ShiftRoster> entries = new ArrayList<>();
        for (RawRow r : result.rows()) {
            for (LocalDate d = r.fromDate(); !d.isAfter(r.toDate()); d = d.plusDays(1)) {
                ShiftRoster s = new ShiftRoster();
                s.setTeamId(teamId);
                s.setEmail(r.email());
                s.setShiftDate(d);
                s.setShiftStart(r.shiftStart());
                s.setShiftEnd(r.shiftEnd());
                s.setCreatedAt(java.time.LocalDateTime.now());
                entries.add(s);
            }
        }

        // Replace existing data for this team month-by-month, then save fresh entries
        Set<Integer> monthKeys = entries.stream()
            .map(s -> s.getShiftDate().getYear() * 100 + s.getShiftDate().getMonthValue())
            .collect(Collectors.toSet());

        for (int key : monthKeys) {
            int year = key / 100, month = key % 100;
            LocalDate start = LocalDate.of(year, month, 1);
            rosterRepo.deleteByMonthRange(teamId, start, start.plusMonths(1));
        }
        rosterRepo.saveAll(entries);

        log.info("Saved {} entries across {} month(s)", entries.size(), monthKeys.size());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success",        true);
        resp.put("entriesLoaded",  entries.size());
        resp.put("monthsReplaced", monthKeys.size());
        resp.put("warnings",       result.warnings());
        resp.put("message",        entries.size() + " shift entries saved successfully."
            + (result.warnings().isEmpty() ? "" : " " + result.warnings().size() + " row(s) skipped."));
        return resp;
    }

    // -----------------------------------------------------------------------
    // Core parser — reads Excel rows, returns RawRow list + warnings
    // -----------------------------------------------------------------------
    private ParseResult parseFile(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        boolean isXlsx = filename.endsWith(".xlsx");

        List<RawRow>  rows     = new ArrayList<>();
        List<String>  warnings = new ArrayList<>();

        try (Workbook wb = isXlsx
                ? new XSSFWorkbook(file.getInputStream())
                : new HSSFWorkbook(file.getInputStream())) {

            Sheet sheet = wb.getSheetAt(0);
            Row   header = sheet.getRow(0);
            if (header == null) throw new IllegalArgumentException("Excel has no header row");

            // collect actual header names for error reporting
            List<String> actualHeaders = new ArrayList<>();
            for (int c = 0; c <= header.getLastCellNum(); c++) {
                String h = cellStr(header.getCell(c)).trim();
                if (!h.isBlank()) actualHeaders.add(h);
            }

            Map<String, Integer> cols = detectColumns(header);

            // validate all required columns are present, report missing ones clearly
            List<String> required = List.of("email", "shiftStart", "shiftEnd", "from", "to");
            Map<String, String> labels = Map.of(
                "email",      "Email",
                "shiftStart", "Shift Start",
                "shiftEnd",   "Shift End",
                "from",       "From Date",
                "to",         "To Date"
            );
            List<String> missing = required.stream()
                .filter(k -> !cols.containsKey(k))
                .map(labels::get)
                .collect(Collectors.toList());

            if (!missing.isEmpty()) {
                throw new IllegalArgumentException(
                    "HEADER_MISMATCH|" +
                    "Missing: " + String.join(", ", missing) + "|" +
                    "Found in file: " + String.join(", ", actualHeaders) + "|" +
                    "Required: Email, Shift Start, Shift End, From Date, To Date"
                );
            }

            int emailCol  = cols.get("email");
            int fromCol   = cols.get("from");
            int toCol     = cols.get("to");
            int startCol  = cols.get("shiftStart");
            int endCol    = cols.get("shiftEnd");

            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null || isBlankRow(row)) continue;
                try {
                    String email = cellStr(row.getCell(emailCol)).toLowerCase().trim();
                    if (email.isBlank() || !email.contains("@")) continue;

                    LocalDate from  = parseDate(row.getCell(fromCol));
                    LocalDate to    = parseDate(row.getCell(toCol));
                    LocalTime start = parseTime(row.getCell(startCol));
                    LocalTime end   = parseTime(row.getCell(endCol));

                    if (to.isBefore(from)) {
                        warnings.add("Row " + (r + 1) + ": 'To Date' is before 'From Date' for " + email + " — skipped");
                        continue;
                    }

                    int days = (int) (to.toEpochDay() - from.toEpochDay() + 1);
                    rows.add(new RawRow(email, start, end, from, to, days));

                } catch (Exception e) {
                    warnings.add("Row " + (r + 1) + " skipped: " + e.getMessage());
                }
            }
        }
        return new ParseResult(rows, warnings);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------
    private Map<String, Integer> detectColumns(Row header) {
        Map<String, Integer> cols = new HashMap<>();
        for (int c = 0; c <= header.getLastCellNum(); c++) {
            String h = cellStr(header.getCell(c)).toLowerCase().trim();
            if      (h.contains("email"))                             cols.put("email",      c);
            else if (h.contains("from") || h.equals("start date"))   cols.put("from",       c);
            else if (h.contains("to")   || h.equals("end date"))     cols.put("to",         c);
            else if (h.contains("start") && !h.contains("date"))     cols.put("shiftStart", c);
            else if ((h.contains("end") || h.contains("finish")) && !h.contains("date")) cols.put("shiftEnd", c);
        }
        return cols;
    }

    private boolean isBlankRow(Row row) {
        for (int c = row.getFirstCellNum(); c <= row.getLastCellNum(); c++)
            if (!cellStr(row.getCell(c)).isBlank()) return false;
        return true;
    }

    private String cellStr(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell))
                    yield cell.getLocalDateTimeCellValue().toLocalDate().toString();
                double v = cell.getNumericCellValue();
                yield v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try   { yield cell.getStringCellValue(); }
                catch (Exception e) { yield String.valueOf(cell.getNumericCellValue()); }
            }
            default -> "";
        };
    }

    private LocalDate parseDate(Cell cell) {
        if (cell == null) throw new IllegalArgumentException("Date cell is empty");
        if (cell.getCellType() == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell))
                return cell.getLocalDateTimeCellValue().toLocalDate();
            return DateUtil.getJavaDate(cell.getNumericCellValue())
                .toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        }
        String s = cellStr(cell).trim();
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try { return LocalDate.parse(s, fmt); } catch (Exception ignored) {}
        }
        throw new IllegalArgumentException("Cannot parse date: '" + s + "'");
    }

    private LocalTime parseTime(Cell cell) {
        if (cell == null) throw new IllegalArgumentException("Time cell is empty");
        if (cell.getCellType() == CellType.NUMERIC) {
            double frac = cell.getNumericCellValue() % 1;
            if (frac < 0) frac += 1;
            return LocalTime.ofSecondOfDay((int) Math.round(frac * 86400) % 86400);
        }
        String s = cellStr(cell).trim();
        if (s.matches("\\d{1,2}:\\d{2}"))        return LocalTime.parse(s, DateTimeFormatter.ofPattern("H:mm"));
        if (s.matches("\\d{1,2}:\\d{2}:\\d{2}")) return LocalTime.parse(s, DateTimeFormatter.ofPattern("H:mm:ss"));
        try { return LocalTime.parse(s, DateTimeFormatter.ofPattern("h:mm a")); } catch (Exception ignored) {}
        throw new IllegalArgumentException("Cannot parse time: '" + s + "'");
    }
}
