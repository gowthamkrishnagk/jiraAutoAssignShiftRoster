package com.jira.autoassign.controller;

import com.jira.autoassign.entity.AssignmentLog;
import com.jira.autoassign.entity.ShiftRoster;
import com.jira.autoassign.entity.Team;
import com.jira.autoassign.repository.AssignmentLogRepository;
import com.jira.autoassign.repository.TeamRepository;
import com.jira.autoassign.service.ExcelService;
import com.jira.autoassign.service.JiraConfigService;
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
    private final AssignmentLogRepository logRepository;
    private final TeamRepository teamRepository;
    private final JiraConfigService jiraConfigService;

    public UploadController(ExcelService excelService, ShiftAssignService shiftAssignService,
                            AssignmentLogRepository logRepository, TeamRepository teamRepository,
                            JiraConfigService jiraConfigService) {
        this.excelService       = excelService;
        this.shiftAssignService = shiftAssignService;
        this.logRepository      = logRepository;
        this.teamRepository     = teamRepository;
        this.jiraConfigService  = jiraConfigService;
    }

    // -----------------------------------------------------------------------
    // Team management
    // -----------------------------------------------------------------------

    @GetMapping("/teams")
    public ResponseEntity<List<Map<String, Object>>> listTeams() {
        return ResponseEntity.ok(
            teamRepository.findAll().stream().map(t -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",     t.getId());
                m.put("name",   t.getName());
                m.put("jql",    t.getJql() != null ? t.getJql() : "");
                m.put("dryRun", t.isDryRun());
                return m;
            }).collect(Collectors.toList())
        );
    }

    @PostMapping("/teams")
    public ResponseEntity<?> addTeam(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        String jql  = body.get("jql");
        if (name == null || name.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Team name is required"));
        if (jql == null || jql.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "JQL is required"));

        Team team = new Team(null, name.trim(), jql.trim());
        teamRepository.save(team);
        return ResponseEntity.ok(Map.of("id", team.getId(), "name", team.getName()));
    }

    @PutMapping("/teams/{id}")
    public ResponseEntity<?> updateTeam(@PathVariable String id, @RequestBody Map<String, String> body) {
        Team team = teamRepository.findById(id).orElse(null);
        if (team == null)
            return ResponseEntity.status(404).body(Map.of("error", "Team not found"));
        String jql  = body.get("jql");
        String name = body.get("name");
        if (jql  != null && !jql.isBlank())  team.setJql(jql.trim());
        if (name != null && !name.isBlank()) team.setName(name.trim());
        return ResponseEntity.ok(Map.of("id", team.getId(), "name", team.getName(), "jql", team.getJql()));
    }

    @DeleteMapping("/teams/{id}")
    public ResponseEntity<?> deleteTeam(@PathVariable String id) {
        if (!teamRepository.existsById(id))
            return ResponseEntity.status(404).body(Map.of("error", "Team not found"));
        teamRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    // -----------------------------------------------------------------------
    // Excel upload (scoped to a team)
    // -----------------------------------------------------------------------

    /** Preview parsed rows — nothing saved. teamId is not required here. */
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

    /** Confirm — expand date ranges and save for the given team. */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "team", required = false, defaultValue = "orderfallout") String teamId) {

        if (file.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "No file provided"));

        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!name.endsWith(".xlsx") && !name.endsWith(".xls"))
            return ResponseEntity.badRequest().body(Map.of("error", "Only .xlsx / .xls files accepted"));

        if (!teamRepository.existsById(teamId))
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown team: " + teamId));

        try {
            return ResponseEntity.ok(excelService.processExcel(file, teamId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(buildError(e));
        }
    }

    // -----------------------------------------------------------------------
    // Status (per team)
    // -----------------------------------------------------------------------

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(
            @RequestParam(value = "team", required = false, defaultValue = "orderfallout") String teamId) {

        List<ShiftRoster> active = shiftAssignService.getActiveShifts(teamId);
        Set<String> paused       = shiftAssignService.getPausedEmails(teamId);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("teamId",      teamId);
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

    // -----------------------------------------------------------------------
    // Pause / resume (per team)
    // -----------------------------------------------------------------------

    @PostMapping("/shift/pause")
    public ResponseEntity<Map<String, Object>> pauseAssignee(@RequestBody Map<String, String> body) {
        String email  = body.get("email");
        String teamId = body.getOrDefault("teamId", "orderfallout");
        if (email == null || email.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'email' field"));
        shiftAssignService.pauseEmail(teamId, email);
        return ResponseEntity.ok(Map.of("paused", true, "email", email, "teamId", teamId));
    }

    @PostMapping("/shift/resume")
    public ResponseEntity<Map<String, Object>> resumeAssignee(@RequestBody Map<String, String> body) {
        String email  = body.get("email");
        String teamId = body.getOrDefault("teamId", "orderfallout");
        if (email == null || email.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'email' field"));
        shiftAssignService.resumeEmail(teamId, email);
        return ResponseEntity.ok(Map.of("paused", false, "email", email, "teamId", teamId));
    }

    // -----------------------------------------------------------------------
    // Activity log (global — all teams)
    // -----------------------------------------------------------------------

    @GetMapping("/activity")
    public ResponseEntity<List<Map<String, String>>> activity() {
        return ResponseEntity.ok(
            logRepository.findTop100ByOrderByAssignedAtDesc().stream().map(l -> {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("teamId",          l.getTeamId() != null ? l.getTeamId() : "");
                m.put("issueKey",        l.getIssueKey());
                m.put("issueSummary",    l.getIssueSummary() == null ? "" : l.getIssueSummary());
                m.put("assignedToEmail", l.getAssignedToEmail());
                m.put("action",          l.getAction());
                m.put("assignedAt",      l.getAssignedAt().toString());
                return m;
            }).collect(Collectors.toList())
        );
    }

    // -----------------------------------------------------------------------
    // Schedule (per team)
    // -----------------------------------------------------------------------

    @GetMapping("/schedule")
    public ResponseEntity<List<Map<String, String>>> schedule(
            @RequestParam(value = "team", required = false, defaultValue = "orderfallout") String teamId) {
        return ResponseEntity.ok(
            shiftAssignService.getCurrentMonthSchedule(teamId).stream().map(s -> {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("email", s.getEmail());
                m.put("date",  s.getShiftDate().toString());
                m.put("start", s.getShiftStart().toString());
                m.put("end",   s.getShiftEnd().toString());
                return m;
            }).collect(Collectors.toList())
        );
    }

    // -----------------------------------------------------------------------
    // Config — dry-run is per-team
    // -----------------------------------------------------------------------

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig(
            @RequestParam(value = "team", required = false, defaultValue = "orderfallout") String teamId) {
        Team team = teamRepository.findById(teamId).orElse(null);
        boolean dryRun = team != null && team.isDryRun();
        return ResponseEntity.ok(Map.of("dryRun", dryRun, "teamId", teamId));
    }

    @PostMapping("/config/dry-run")
    public ResponseEntity<Map<String, Object>> setDryRun(@RequestBody Map<String, Object> body) {
        Object value  = body.get("dryRun");
        Object teamIdObj = body.get("teamId");
        if (value == null)
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'dryRun' field"));

        String teamId = teamIdObj != null ? teamIdObj.toString() : "orderfallout";
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null)
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown team: " + teamId));

        boolean enabled = Boolean.parseBoolean(value.toString());
        team.setDryRun(enabled);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("dryRun", enabled);
        resp.put("teamId", teamId);
        resp.put("message", enabled
            ? "Dry-run ENABLED for " + team.getName() + " — no tickets will be assigned."
            : "Dry-run DISABLED for " + team.getName() + " — assignments are live.");
        return ResponseEntity.ok(resp);
    }

    // -----------------------------------------------------------------------
    // Jira connection settings
    // -----------------------------------------------------------------------

    @GetMapping("/jira-settings")
    public ResponseEntity<Map<String, Object>> getJiraSettings() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("configured",  jiraConfigService.isConfigured());
        resp.put("jiraEmail",   jiraConfigService.getEmail() != null ? jiraConfigService.getEmail() : "");
        resp.put("apiTokenSet", jiraConfigService.getApiToken() != null && !jiraConfigService.getApiToken().isBlank());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/jira-settings")
    public ResponseEntity<?> saveJiraSettings(@RequestBody Map<String, String> body) {
        String jiraEmail = body.get("jiraEmail");
        String apiToken  = body.get("apiToken");

        if (jiraEmail == null || jiraEmail.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
        if (apiToken == null || apiToken.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "API token is required"));

        jiraConfigService.save(jiraEmail, apiToken);
        return ResponseEntity.ok(Map.of("saved", true, "configured", true));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

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
}
