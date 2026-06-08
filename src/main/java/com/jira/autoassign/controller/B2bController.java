package com.jira.autoassign.controller;

import com.jira.autoassign.entity.B2bMember;
import com.jira.autoassign.repository.B2bMemberRepository;
import com.jira.autoassign.service.JiraConfigService;
import com.jira.autoassign.service.WebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Admin endpoints for the B2B monitor tab:
 *  - Jira→Teams member mapping CRUD (with Matrixx/Aria support flags)
 *  - B2B webhook URL settings + test
 */
@RestController
@RequestMapping("/api/b2b")
public class B2bController {

    private final B2bMemberRepository memberRepository;
    private final JiraConfigService   configService;
    private final WebhookService      webhookService;

    public B2bController(B2bMemberRepository memberRepository,
                         JiraConfigService configService,
                         WebhookService webhookService) {
        this.memberRepository = memberRepository;
        this.configService    = configService;
        this.webhookService   = webhookService;
    }

    // ----------------------------------------------------------------------- members

    @GetMapping("/members")
    public ResponseEntity<List<Map<String, Object>>> listMembers() {
        return ResponseEntity.ok(
            memberRepository.findAll().stream().map(B2bController::toMap).collect(Collectors.toList()));
    }

    @PostMapping("/members")
    public ResponseEntity<?> addMember(@RequestBody Map<String, Object> body) {
        B2bMember m = new B2bMember();
        apply(m, body);
        if ((m.getJiraEmail() == null || m.getJiraEmail().isBlank())
                && (m.getJiraName() == null || m.getJiraName().isBlank()))
            return ResponseEntity.badRequest().body(Map.of("error", "Jira email or Jira name is required"));
        if (m.getTeamsEmail() == null || m.getTeamsEmail().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Teams email is required"));
        memberRepository.save(m);
        return ResponseEntity.ok(toMap(m));
    }

    @PutMapping("/members/{id}")
    public ResponseEntity<?> updateMember(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        B2bMember m = memberRepository.findById(id).orElse(null);
        if (m == null) return ResponseEntity.status(404).body(Map.of("error", "Member not found"));
        apply(m, body);
        memberRepository.save(m);
        return ResponseEntity.ok(toMap(m));
    }

    /**
     * Bulk upsert members. Body: { "rows": [ { jiraEmail, jiraName, teamsEmail, teamsName,
     * matrixxSupport, ariaSupport }, ... ] }. Matches existing rows by jiraEmail (case-insensitive)
     * and updates them; otherwise inserts. Rows without a Teams email or any Jira identity are skipped.
     */
    @PostMapping("/members/bulk")
    public ResponseEntity<?> bulkUpsert(@RequestBody Map<String, Object> body) {
        Object rowsObj = body.get("rows");
        if (!(rowsObj instanceof List<?> rows))
            return ResponseEntity.badRequest().body(Map.of("error", "Expected a 'rows' array"));

        int inserted = 0, updated = 0, skipped = 0;
        for (Object o : rows) {
            if (!(o instanceof Map<?, ?> raw)) { skipped++; continue; }
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) raw;

            String jiraEmail = str(row.get("jiraEmail")).toLowerCase();
            String jiraName  = str(row.get("jiraName"));
            String teamsEmail = str(row.get("teamsEmail"));
            if (teamsEmail.isEmpty() || (jiraEmail.isEmpty() && jiraName.isEmpty())) { skipped++; continue; }

            B2bMember m = jiraEmail.isEmpty() ? null
                : memberRepository.findByJiraEmailIgnoreCase(jiraEmail).orElse(null);
            boolean isNew = (m == null);
            if (isNew) m = new B2bMember();
            apply(m, row);
            memberRepository.save(m);
            if (isNew) inserted++; else updated++;
        }
        return ResponseEntity.ok(Map.of("inserted", inserted, "updated", updated, "skipped", skipped));
    }

    @DeleteMapping("/members/{id}")
    public ResponseEntity<?> deleteMember(@PathVariable Long id) {
        if (!memberRepository.existsById(id))
            return ResponseEntity.status(404).body(Map.of("error", "Member not found"));
        memberRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    // ----------------------------------------------------------------------- webhook

    @GetMapping("/webhook-settings")
    public ResponseEntity<Map<String, Object>> getWebhookSettings() {
        String url = configService.getB2bWebhookUrl();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("webhookUrl",  url);
        resp.put("teamsDomain", configService.getB2bTeamsDomain());
        resp.put("configured",  url != null && !url.isBlank());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/webhook-settings")
    public ResponseEntity<?> saveWebhookSettings(@RequestBody Map<String, String> body) {
        String url = body.getOrDefault("webhookUrl", "").trim();
        configService.saveB2bWebhookUrl(url);
        if (body.containsKey("teamsDomain")) {
            configService.saveB2bTeamsDomain(body.getOrDefault("teamsDomain", "").trim());
        }
        return ResponseEntity.ok(Map.of("saved", true, "webhookUrl", url,
            "teamsDomain", configService.getB2bTeamsDomain(), "configured", !url.isBlank()));
    }

    @PostMapping("/webhook-settings/test")
    public ResponseEntity<?> testWebhook(@RequestBody Map<String, String> body) {
        String url = body.getOrDefault("webhookUrl", "").trim();
        if (url.isBlank()) url = configService.getB2bWebhookUrl();
        if (url == null || url.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "No B2B webhook URL configured"));

        int status = webhookService.testB2bWebhook(url);
        if (status >= 200 && status < 300) {
            return ResponseEntity.ok(Map.of("success", true, "httpStatus", status));
        } else if (status == -1) {
            return ResponseEntity.status(502).body(Map.of("success", false,
                "error", "Could not reach webhook URL (connection failed or timeout)"));
        } else {
            return ResponseEntity.status(502).body(Map.of("success", false,
                "httpStatus", status, "error", "Webhook returned non-2xx status: " + status));
        }
    }

    // ----------------------------------------------------------------------- helpers

    private static void apply(B2bMember m, Map<String, Object> body) {
        if (body.containsKey("jiraEmail"))
            m.setJiraEmail(str(body.get("jiraEmail")).toLowerCase());
        if (body.containsKey("jiraName"))   m.setJiraName(str(body.get("jiraName")));
        if (body.containsKey("teamsEmail")) m.setTeamsEmail(str(body.get("teamsEmail")));
        if (body.containsKey("teamsName"))  m.setTeamsName(str(body.get("teamsName")));
        if (body.containsKey("matrixxSupport")) m.setMatrixxSupport(bool(body.get("matrixxSupport")));
        if (body.containsKey("ariaSupport"))    m.setAriaSupport(bool(body.get("ariaSupport")));
    }

    private static String str(Object o) { return o == null ? "" : o.toString().trim(); }

    private static boolean bool(Object o) {
        if (o instanceof Boolean b) return b;
        return o != null && Boolean.parseBoolean(o.toString());
    }

    private static Map<String, Object> toMap(B2bMember m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id",             m.getId());
        map.put("jiraEmail",      m.getJiraEmail()  != null ? m.getJiraEmail()  : "");
        map.put("jiraName",       m.getJiraName()   != null ? m.getJiraName()   : "");
        map.put("teamsEmail",     m.getTeamsEmail() != null ? m.getTeamsEmail() : "");
        map.put("teamsName",      m.getTeamsName()  != null ? m.getTeamsName()  : "");
        map.put("matrixxSupport", m.isMatrixxSupport());
        map.put("ariaSupport",    m.isAriaSupport());
        return map;
    }
}
