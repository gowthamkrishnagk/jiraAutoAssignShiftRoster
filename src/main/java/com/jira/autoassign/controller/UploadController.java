package com.jira.autoassign.controller;

import com.jira.autoassign.entity.ShiftRoster;
import com.jira.autoassign.service.ExcelService;
import com.jira.autoassign.service.ShiftAssignService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class UploadController {

    private final ExcelService excelService;
    private final ShiftAssignService shiftAssignService;

    public UploadController(ExcelService excelService, ShiftAssignService shiftAssignService) {
        this.excelService       = excelService;
        this.shiftAssignService = shiftAssignService;
    }

    /** Preview parsed rows — nothing saved to DB. */
    @PostMapping("/preview")
    public ResponseEntity<Map<String, Object>> preview(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "No file provided"));
        try {
            return ResponseEntity.ok(excelService.previewExcel(file));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(buildError(e));
        }
    }

    /** Confirm — expand date ranges and save to DB. */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "No file provided"));

        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!name.endsWith(".xlsx") && !name.endsWith(".xls"))
            return ResponseEntity.badRequest().body(Map.of("error", "Only .xlsx / .xls files accepted"));

        try {
            return ResponseEntity.ok(excelService.processExcel(file));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(buildError(e));
        }
    }

    /** Parses HEADER_MISMATCH errors into structured fields for the UI. */
    private Map<String, Object> buildError(Exception e) {
        String msg = e.getMessage() == null ? "Unknown error" : e.getMessage();
        if (msg.startsWith("HEADER_MISMATCH|")) {
            String[] parts = msg.split("\\|");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error",        "header_mismatch");
            body.put("missing",      parts.length > 1 ? parts[1] : "");
            body.put("foundHeaders", parts.length > 2 ? parts[2] : "");
            body.put("required",     parts.length > 3 ? parts[3] : "");
            return body;
        }
        return Map.of("error", msg);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        List<ShiftRoster> active = shiftAssignService.getActiveShifts();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("currentTime", LocalDateTime.now().toString());
        resp.put("activeCount", active.size());
        resp.put("activeAssignees", active.stream().map(s -> {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("email",      s.getEmail());
            m.put("shiftDate",  s.getShiftDate().toString());
            m.put("shiftStart", s.getShiftStart().toString());
            m.put("shiftEnd",   s.getShiftEnd().toString());
            return m;
        }).collect(Collectors.toList()));
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/schedule")
    public ResponseEntity<List<Map<String, String>>> schedule() {
        return ResponseEntity.ok(
            shiftAssignService.getCurrentMonthSchedule().stream().map(s -> {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("email", s.getEmail());
                m.put("date",  s.getShiftDate().toString());
                m.put("start", s.getShiftStart().toString());
                m.put("end",   s.getShiftEnd().toString());
                return m;
            }).collect(Collectors.toList())
        );
    }
}
