package com.jira.autoassign.controller;

import com.jira.autoassign.entity.AssignmentLog;
import com.jira.autoassign.entity.ShiftRoster;
import com.jira.autoassign.entity.Team;
import com.jira.autoassign.repository.AssignmentLogRepository;
import com.jira.autoassign.repository.TeamRepository;
import com.jira.autoassign.scheduler.AssignScheduler;
import com.jira.autoassign.service.ExcelService;
import com.jira.autoassign.service.JiraConfigService;
import com.jira.autoassign.service.ShiftAssignService;
import com.jira.autoassign.service.WebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
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
    private final WebhookService webhookService;
    private final AssignScheduler assignScheduler;

    public UploadController(ExcelService excelService, ShiftAssignService shiftAssignService,
                            AssignmentLogRepository logRepository, TeamRepository teamRepository,
                            JiraConfigService jiraConfigService, WebhookService webhookService,
                            AssignScheduler assignScheduler) {
        this.excelService       = excelService;
        this.shiftAssignService = shiftAssignService;
        this.logRepository      = logRepository;
        this.teamRepository     = teamRepository;
        this.jiraConfigService  = jiraConfigService;
        this.webhookService     = webhookService;
        this.assignScheduler    = assignScheduler;
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
                m.put("autoAssign", t.isAutoAssign());
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
        // Monitor-only teams (B2B) have a fixed JQL — never editable via the API.
        if (jql != null && !jql.isBlank()) {
            if (!team.isAutoAssign())
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "JQL is fixed for monitor-only teams and cannot be edited"));
            team.setJql(jql.trim());
        }
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
    // Round-robin rotation for one team — powers the "Assignment History" view.
    // Returns the rotation order (who's in the cycle, in the exact order the
    // scheduler uses), which slot is next, and the recent assignment sequence
    // so the 1→2→3→1→2→3 pattern is visible.
    // -----------------------------------------------------------------------

    @GetMapping("/rotation")
    public ResponseEntity<Map<String, Object>> rotation(
            @RequestParam(value = "team", required = false, defaultValue = "orderfallout") String teamId) {

        Set<String> paused       = shiftAssignService.getPausedEmails(teamId);
        List<ShiftRoster> active = shiftAssignService.getActiveShifts(teamId);

        // Cover map: owner (lower) → substitute who actually receives the ticket.
        Map<String, String> coverByOwner = new HashMap<>();
        for (ShiftRoster s : active) {
            if (s.getCoverEmail() != null && !s.getCoverEmail().isBlank())
                coverByOwner.put(s.getEmail().toLowerCase().trim(), s.getCoverEmail().trim());
        }

        // Rotation order = active owners, not paused, deduped, sorted by email —
        // the exact ordering the scheduler round-robins over (ShiftAssignService sorts
        // its working list by owner email before indexing).
        List<ShiftRoster> uniq = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ShiftRoster s : active) {
            String ownerKey = s.getEmail().toLowerCase().trim();
            if (paused.contains(ownerKey)) continue;
            if (!seen.add(ownerKey)) continue;
            uniq.add(s);
        }
        uniq.sort(Comparator.comparing(ShiftRoster::getEmail));

        List<Map<String, Object>> order = new ArrayList<>();
        for (ShiftRoster s : uniq) {
            String cover = coverByOwner.get(s.getEmail().toLowerCase().trim());
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name",    ShiftAssignService.displayNameFromEmail(s.getEmail()));
            p.put("email",   s.getEmail());
            p.put("uses",    cover != null ? cover : s.getEmail());
            p.put("covered", cover != null);
            order.add(p);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("teamId",    teamId);
        resp.put("order",     order);
        resp.put("nextIndex", order.isEmpty() ? -1 : Math.floorMod(shiftAssignService.getRrIndex(teamId), order.size()));

        // Recent assignment sequence (chronological: oldest → newest) so the cycle reads left-to-right.
        List<AssignmentLog> recent =
            logRepository.findTop24ByTeamIdAndActionOrderByAssignedAtDescIdDesc(teamId, "ASSIGN");
        List<Map<String, Object>> history = new ArrayList<>();
        for (int i = recent.size() - 1; i >= 0; i--) {
            AssignmentLog l = recent.get(i);
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("name",     ShiftAssignService.displayNameFromEmail(l.getAssignedToEmail()));
            h.put("email",    l.getAssignedToEmail());
            h.put("issueKey", l.getIssueKey());
            h.put("at",       l.getAssignedAt().toString());
            history.add(h);
        }
        resp.put("history", history);

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
            logRepository.findTop100ByOrderByAssignedAtDescIdDesc().stream().map(l -> {
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
    // Roster edit + break history (per team, today only)
    // -----------------------------------------------------------------------

    @GetMapping("/roster-events")
    public ResponseEntity<List<Map<String, Object>>> rosterEvents(
            @RequestParam(value = "team", required = false, defaultValue = "orderfallout") String teamId) {
        return ResponseEntity.ok(shiftAssignService.getTodayRosterEvents(teamId));
    }

    // -----------------------------------------------------------------------
    // Schedule (per team)
    // -----------------------------------------------------------------------

    @GetMapping("/schedule")
    public ResponseEntity<List<Map<String, Object>>> schedule(
            @RequestParam(value = "team", required = false, defaultValue = "orderfallout") String teamId) {
        return ResponseEntity.ok(
            shiftAssignService.getCurrentMonthSchedule(teamId).stream().map(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",    s.getId());
                m.put("email", s.getEmail());
                m.put("date",  s.getShiftDate().toString());
                m.put("start", s.getShiftStart().toString());
                m.put("end",   s.getShiftEnd().toString());
                return m;
            }).collect(Collectors.toList())
        );
    }

    // -----------------------------------------------------------------------
    // Today's shift (all rows: active, break, upcoming, done)
    // -----------------------------------------------------------------------

    @GetMapping("/shift/today")
    public ResponseEntity<List<Map<String, Object>>> todayShifts(
            @RequestParam(value = "team", required = false, defaultValue = "orderfallout") String teamId) {

        LocalDate today     = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        LocalTime now       = LocalTime.now();
        Set<String> paused  = shiftAssignService.getPausedEmails(teamId);

        List<Map<String, Object>> result = new ArrayList<>();

        // Dedupe key = email|start|end. Collapses both genuine duplicate roster rows
        // (same person + same shift inserted twice) and the overnight yesterday/today
        // pair (identical times → identical key). First occurrence wins; yesterday's
        // still-running row is added first so it keeps the correct active/break status.
        Set<String> seen = new HashSet<>();

        // ── Yesterday's overnight shifts still active now ─────────────────────
        // e.g. shift stored on 2026-05-27 with start=22:30, end=07:30
        // → at 00:23 on 2026-05-28 the shift is still running (end hasn't passed)
        for (ShiftRoster s : shiftAssignService.getShiftsForDate(teamId, yesterday)) {
            boolean overnight = s.getShiftStart().isAfter(s.getShiftEnd());
            if (overnight && s.getShiftEnd().isAfter(now)) {
                if (!seen.add(dedupeKey(s))) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",    s.getId());
                m.put("email", s.getEmail());
                m.put("start", s.getShiftStart().toString());
                m.put("end",   s.getShiftEnd().toString());
                // Definitely started (it started yesterday); check only pause state
                m.put("status", paused.contains(s.getEmail().toLowerCase().trim()) ? "break" : "active");
                putCover(m, s);
                result.add(m);
            }
        }

        // ── Today's shifts ────────────────────────────────────────────────────
        for (ShiftRoster s : shiftAssignService.getTodayShifts(teamId)) {
            if (!seen.add(dedupeKey(s))) continue;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",    s.getId());
            m.put("email", s.getEmail());
            m.put("start", s.getShiftStart().toString());
            m.put("end",   s.getShiftEnd().toString());

            boolean overnight  = s.getShiftStart().isAfter(s.getShiftEnd());
            boolean started    = !now.isBefore(s.getShiftStart());
            // Overnight shifts starting today end tomorrow — never "done" today
            boolean shiftEnded = !overnight && now.isAfter(s.getShiftEnd());

            String status;
            if (shiftEnded) {
                status = "done";
            } else if (started && paused.contains(s.getEmail().toLowerCase().trim())) {
                status = "break";
            } else if (!started) {
                status = "upcoming";
            } else {
                status = "active";
            }
            m.put("status", status);
            putCover(m, s);
            result.add(m);
        }

        result.sort(Comparator.comparingInt(m -> statusOrder((String) m.get("status"))));
        return ResponseEntity.ok(result);
    }

    /**
     * Adds cover state to a Today-panel row. The substitute's email is never exposed —
     * only a boolean and the substitute's display name (for the manage-cover dialog).
     */
    private static void putCover(Map<String, Object> m, ShiftRoster s) {
        boolean covered = s.getCoverEmail() != null && !s.getCoverEmail().isBlank();
        m.put("covered",    covered);
        m.put("coverEmail", covered ? s.getCoverEmail() : null);
        m.put("coverName",  covered ? ShiftAssignService.displayNameFromEmail(s.getCoverEmail()) : null);
        // Display name of the actual shift owner (what the UI shows as the row title).
        m.put("ownerName",  ShiftAssignService.displayNameFromEmail(s.getEmail()));
        // Flags a row whose email isn't a real address (e.g. a name typed in) — it can
        // never receive a Jira ticket, so the UI shows a ⚠ and prompts a fix.
        m.put("validEmail", ShiftAssignService.isValidEmail(s.getEmail()));
    }

    /** Identity for deduping shift rows in the Today panel: same person + same shift window. */
    private static String dedupeKey(ShiftRoster s) {
        return s.getEmail().toLowerCase().trim() + "|" + s.getShiftStart() + "|" + s.getShiftEnd();
    }

    private static int statusOrder(String s) {
        return switch (s) {
            case "active"   -> 0;
            case "break"    -> 1;
            case "upcoming" -> 2;
            case "done"     -> 3;
            default         -> 4;
        };
    }

    // -----------------------------------------------------------------------
    // Remove a single shift row (used by "Remove from today's shift")
    // -----------------------------------------------------------------------

    @DeleteMapping("/shift/{id}")
    public ResponseEntity<?> removeShiftRow(
            @PathVariable Long id,
            @RequestParam(value = "team", required = false, defaultValue = "orderfallout") String teamId) {
        try {
            shiftAssignService.removeShiftRow(id, teamId);
            return ResponseEntity.ok(Map.of("removed", true, "id", id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // -----------------------------------------------------------------------
    // Add a single shift row for today (used by "Add to today's shift")
    // -----------------------------------------------------------------------

    @PostMapping("/shift/add")
    public ResponseEntity<?> addShiftRow(@RequestBody Map<String, Object> body) {
        Object rawEmail = body.get("email");
        Object rawStart = body.get("start");
        Object rawEnd   = body.get("end");
        Object rawTeam  = body.get("teamId");

        if (rawEmail == null || rawEmail.toString().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'email'"));
        if (rawStart == null || rawEnd == null)
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'start' or 'end'"));

        String teamId = rawTeam != null ? rawTeam.toString() : "orderfallout";
        try {
            LocalTime start = LocalTime.parse(rawStart.toString());
            LocalTime end   = LocalTime.parse(rawEnd.toString());
            Map<String, Object> result =
                shiftAssignService.addShiftRow(teamId, rawEmail.toString(), start, end);
            return ResponseEntity.ok(result);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "start/end must be HH:mm time values"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // -----------------------------------------------------------------------
    // Cover a shift row — owner stays displayed, tickets route to a substitute
    // -----------------------------------------------------------------------

    @PostMapping("/shift/cover")
    public ResponseEntity<?> coverShiftRow(@RequestBody Map<String, Object> body) {
        Object rawId    = body.get("id");
        Object rawCover = body.get("coverEmail");   // blank/absent → clear the cover
        Object rawTeam  = body.get("teamId");

        if (rawId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'id'"));

        String teamId = rawTeam != null ? rawTeam.toString() : "orderfallout";
        try {
            Long id = Long.parseLong(rawId.toString());
            String cover = rawCover == null ? "" : rawCover.toString();
            Map<String, Object> result = shiftAssignService.setCover(id, cover, teamId);
            return ResponseEntity.ok(result);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "id must be a number"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // -----------------------------------------------------------------------
    // Shift swap
    // -----------------------------------------------------------------------

    @PostMapping("/shift/swap")
    public ResponseEntity<?> swapShifts(@RequestBody Map<String, Object> body) {
        Object rawA    = body.get("idA");
        Object rawB    = body.get("idB");
        Object rawTeam = body.get("teamId");

        if (rawA == null || rawB == null)
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'idA' or 'idB'"));

        String teamId = rawTeam != null ? rawTeam.toString() : "orderfallout";
        try {
            Long idA = Long.parseLong(rawA.toString());
            Long idB = Long.parseLong(rawB.toString());
            Map<String, Object> result = shiftAssignService.swapShifts(idA, idB, teamId);
            return ResponseEntity.ok(result);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "idA and idB must be numbers"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/shift/swap-assignee")
    public ResponseEntity<?> swapAssignee(@RequestBody Map<String, Object> body) {
        Object rawFrom = body.get("fromEmail");
        Object rawTo   = body.get("toEmail");
        Object rawTeam = body.get("teamId");

        if (rawFrom == null || rawTo == null || rawFrom.toString().isBlank() || rawTo.toString().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'fromEmail' or 'toEmail'"));

        String teamId = rawTeam != null ? rawTeam.toString() : "orderfallout";
        try {
            Map<String, Object> result = shiftAssignService.swapAssignee(
                rawFrom.toString().trim(), rawTo.toString().trim(), teamId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/shift/replace-email")
    public ResponseEntity<?> replaceShiftEmail(@RequestBody Map<String, Object> body) {
        Object rawId    = body.get("id");
        Object rawEmail = body.get("newEmail");
        Object rawTeam  = body.get("teamId");

        if (rawId == null || rawEmail == null || rawEmail.toString().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'id' or 'newEmail'"));

        String teamId = rawTeam != null ? rawTeam.toString() : "orderfallout";
        try {
            Long id = Long.parseLong(rawId.toString());
            Map<String, Object> result = shiftAssignService.replaceShiftEmail(id, rawEmail.toString().trim(), teamId);
            return ResponseEntity.ok(result);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "id must be a number"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
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
    // Power Automate webhook settings
    // -----------------------------------------------------------------------

    @GetMapping("/webhook-settings")
    public ResponseEntity<Map<String, Object>> getWebhookSettings() {
        String url = jiraConfigService.getWebhookUrl();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("webhookUrl", url);
        resp.put("configured", url != null && !url.isBlank());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/webhook-settings")
    public ResponseEntity<?> saveWebhookSettings(@RequestBody Map<String, String> body) {
        String url = body.getOrDefault("webhookUrl", "").trim();
        jiraConfigService.saveWebhookUrl(url);
        return ResponseEntity.ok(Map.of("saved", true, "webhookUrl", url,
                                        "configured", !url.isBlank()));
    }

    @PostMapping("/webhook-settings/test")
    public ResponseEntity<?> testWebhook(@RequestBody Map<String, String> body) {
        String url = body.getOrDefault("webhookUrl", "").trim();
        if (url.isBlank()) {
            // Fall back to saved URL if caller sent empty
            url = jiraConfigService.getWebhookUrl();
        }
        if (url == null || url.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "No webhook URL configured"));

        int status = webhookService.testWebhook(url);
        if (status >= 200 && status < 300) {
            return ResponseEntity.ok(Map.of("success", true,  "httpStatus", status));
        } else if (status == -1) {
            return ResponseEntity.status(502).body(Map.of("success", false,
                "error", "Could not reach webhook URL (connection failed or timeout)"));
        } else {
            return ResponseEntity.status(502).body(Map.of("success", false,
                "httpStatus", status, "error", "Webhook returned non-2xx status: " + status));
        }
    }

    // -----------------------------------------------------------------------
    // Scheduler status — next trigger time
    // -----------------------------------------------------------------------

    /**
     * Returns upcoming shift boundaries across all teams —
     * i.e. when tickets will next be reassigned and the webhook will fire.
     */
    @GetMapping("/scheduler/handovers")
    public ResponseEntity<Map<String, Object>> schedulerHandovers() {
        return ResponseEntity.ok(Map.of("handovers", shiftAssignService.getUpcomingHandovers()));
    }

    /**
     * Returns when the scheduler last fired and when it will next fire.
     * Cron default is {@code 0 *\/1 * * * *} (every minute at second :00),
     * so "next run" is always the start of the next minute.
     */
    @GetMapping("/scheduler/status")
    public ResponseEntity<Map<String, Object>> schedulerStatus() {
        Instant now      = Instant.now();
        Instant lastRun  = assignScheduler.getLastRunAt();

        // Next :00 mark — i.e. start of the following minute
        Instant nextRun  = now.truncatedTo(ChronoUnit.MINUTES).plusSeconds(60);
        long    secsLeft = Math.max(0, ChronoUnit.SECONDS.between(now, nextRun));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("lastRunAt",        lastRun != null ? lastRun.toString() : null);
        resp.put("nextRunAt",        nextRun.toString());
        resp.put("secondsUntilNext", secsLeft);
        return ResponseEntity.ok(resp);
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
