package com.jira.autoassign.controller;

import com.jira.autoassign.config.JiraProperties;
import com.jira.autoassign.entity.AssignmentLog;
import com.jira.autoassign.entity.ShiftRoster;
import com.jira.autoassign.repository.AssignmentLogRepository;
import com.jira.autoassign.service.ExcelService;
import com.jira.autoassign.service.ShiftAssignService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class UploadController {

    private final ExcelService excelService;
    private final ShiftAssignService shiftAssignService;
    private final AssignmentLogRepository logRepository;
    private final JiraProperties jiraProperties;

    public UploadController(ExcelService excelService, ShiftAssignService shiftAssignService,
                            AssignmentLogRepository logRepository, JiraProperties jiraProperties) {
        this.excelService    = excelService;
        this.shiftAssignService = shiftAssignService;
        this.logRepository   = logRepository;
        this.jiraProperties  = jiraProperties;
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
        Set<String> paused = shiftAssignService.getPausedEmails();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("currentTime", LocalDateTime.now().toString());
        resp.put("activeCount", active.stream().map(ShiftRoster::getEmail).distinct()
                .filter(e -> !paused.contains(e)).count());
        resp.put("activeAssignees", active.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("email",      s.getEmail());
            m.put("shiftDate",  s.getShiftDate().toString());
            m.put("shiftStart", s.getShiftStart().toString());
            m.put("shiftEnd",   s.getShiftEnd().toString());
            m.put("paused",     paused.contains(s.getEmail()));
            return m;
        }).collect(Collectors.toList()));
        return ResponseEntity.ok(resp);
    }

    /** Pause an assignee — they stay on the roster but are skipped during ticket assignment. */
    @PostMapping("/shift/pause")
    public ResponseEntity<Map<String, Object>> pauseAssignee(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'email' field"));
        shiftAssignService.pauseEmail(email);
        return ResponseEntity.ok(Map.of("paused", true, "email", email));
    }

    /** Resume a paused assignee so they receive tickets again. */
    @PostMapping("/shift/resume")
    public ResponseEntity<Map<String, Object>> resumeAssignee(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'email' field"));
        shiftAssignService.resumeEmail(email);
        return ResponseEntity.ok(Map.of("paused", false, "email", email));
    }

    @GetMapping("/activity")
    public ResponseEntity<List<Map<String, String>>> activity() {
        return ResponseEntity.ok(
            logRepository.findTop100ByOrderByAssignedAtDesc().stream().map(l -> {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("issueKey",        l.getIssueKey());
                m.put("issueSummary",    l.getIssueSummary() == null ? "" : l.getIssueSummary());
                m.put("assignedToEmail", l.getAssignedToEmail());
                m.put("action",          l.getAction());
                m.put("assignedAt",      l.getAssignedAt().toString());
                return m;
            }).collect(Collectors.toList())
        );
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

    /** Returns current runtime configuration. */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("dryRun", jiraProperties.isDryRun());
        return ResponseEntity.ok(resp);
    }

    /** Toggles dry-run mode at runtime without restarting. */
    @PostMapping("/config/dry-run")
    public ResponseEntity<Map<String, Object>> setDryRun(@RequestBody Map<String, Boolean> body) {
        Boolean value = body.get("dryRun");
        if (value == null)
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'dryRun' field"));
        jiraProperties.setDryRun(value);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("dryRun", jiraProperties.isDryRun());
        resp.put("message", value ? "Dry-run ENABLED — no tickets will be assigned." : "Dry-run DISABLED — assignments are live.");
        return ResponseEntity.ok(resp);
    }
}
