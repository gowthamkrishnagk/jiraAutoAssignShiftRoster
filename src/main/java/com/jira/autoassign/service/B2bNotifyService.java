package com.jira.autoassign.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jira.autoassign.client.JiraClient;
import com.jira.autoassign.entity.B2bAssignState;
import com.jira.autoassign.entity.B2bMember;
import com.jira.autoassign.entity.Team;
import com.jira.autoassign.repository.B2bAssignStateRepository;
import com.jira.autoassign.repository.B2bMemberRepository;
import com.jira.autoassign.repository.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Watches monitor-only ("not auto-assign") teams — the B2B team — and posts Teams
 * @mention cards on three independent triggers, each deduped via {@link B2bAssignState}:
 *
 *  1. Assignee changed   → mention the new assignee ("work on this as priority").
 *  2. Needs Matrixx/Aria support → mention the current assignee. Triggered when the
 *     assignee is in the Matrixx/Aria support group OR the Escalation Path contains
 *     "matrixx"/"aria".
 *  3. SLA breach warning → mention the current assignee when the SLA will breach within
 *     15 minutes (and hasn't breached yet).
 *
 * The assignee→Teams mapping ({@link B2bMember}) supplies the @mention id. Unmapped
 * assignees still get a card, just with their plain Jira name and no @mention entity.
 */
@Service
public class B2bNotifyService {

    private static final Logger log = LoggerFactory.getLogger(B2bNotifyService.class);

    private static final long SLA_WARN_WINDOW_MS = 15 * 60 * 1000L; // 15 minutes

    private final JiraClient                 jiraClient;
    private final TeamRepository             teamRepository;
    private final B2bMemberRepository        memberRepository;
    private final B2bAssignStateRepository   stateRepository;
    private final WebhookService             webhookService;

    public B2bNotifyService(JiraClient jiraClient, TeamRepository teamRepository,
                            B2bMemberRepository memberRepository,
                            B2bAssignStateRepository stateRepository,
                            WebhookService webhookService) {
        this.jiraClient       = jiraClient;
        this.teamRepository   = teamRepository;
        this.memberRepository = memberRepository;
        this.stateRepository  = stateRepository;
        this.webhookService   = webhookService;
    }

    /** Resolved Teams @mention target for an assignee. */
    private record Mention(String email, String name, boolean has) {}

    public void runAll() {
        List<Team> monitorTeams = teamRepository.findAll().stream()
            .filter(t -> !t.isAutoAssign())
            .toList();
        if (monitorTeams.isEmpty()) return;

        for (Team team : monitorTeams) {
            try {
                runTeam(team);
            } catch (Exception e) {
                log.error("[{}] B2B monitor run failed: {}", team.getName(), e.getMessage(), e);
            }
        }
    }

    private void runTeam(Team team) {
        List<JsonNode> tickets = jiraClient.getB2bTickets(team.getJql());
        log.info("[{}] B2B monitor — {} ticket(s) in scope", team.getName(), tickets.size());

        for (JsonNode ticket : tickets) {
            try {
                processTicket(team, ticket);
            } catch (Exception e) {
                log.warn("[{}] B2B processing failed for {}: {}",
                    team.getName(), ticket.path("key").asText(""), e.getMessage());
            }
        }
    }

    private void processTicket(Team team, JsonNode ticket) {
        String issueKey = ticket.path("key").asText("");
        if (issueKey.isEmpty()) return;
        String summary  = ticket.path("fields").path("summary").asText("");

        JsonNode assignee = ticket.path("fields").path("assignee");
        boolean unassigned = assignee.isNull() || assignee.isMissingNode();
        String currentAccId = unassigned ? "" : assignee.path("accountId").asText("");
        String currentEmail = unassigned ? "" : assignee.path("emailAddress").asText("");
        String currentName  = unassigned ? "" : assignee.path("displayName").asText(currentEmail);
        boolean hasAssignee = !currentAccId.isBlank();

        String escalation = jiraClient.extractEscalationPath(ticket);
        JiraClient.SlaOngoing sla = jiraClient.extractSlaOngoing(ticket);
        String slaFriendly = jiraClient.extractSlaRemaining(ticket);

        Optional<B2bAssignState> existing = stateRepository.findById(issueKey);
        boolean existed = existing.isPresent();
        B2bAssignState st = existing.orElseGet(() -> new B2bAssignState(issueKey));

        Optional<B2bMember> memberOpt = hasAssignee
            ? resolveMember(currentEmail, currentName) : Optional.empty();
        Mention mention = mention(memberOpt, currentName);

        // --- 1. Assignee changed (only for tickets we've seen before) ---
        boolean assigneeChanged = existed && hasAssignee
            && !currentAccId.equals(st.getAccountId());
        if (assigneeChanged) {
            String from = st.getAssigneeName() != null && !st.getAssigneeName().isBlank()
                ? st.getAssigneeName() : "Unassigned";
            Map<String, String> t = ticketMap(issueKey, summary, "Reassigned: " + from + " → " + currentName, slaFriendly);
            String body = mention.has()
                ? "<at>" + mention.name() + "</at> — this is a B2B ticket, please work on it as priority."
                : mention.name() + " — this is a B2B ticket, please work on it as priority.";
            log.info("[{}] {} reassigned {} → {} — notifying", team.getName(), issueKey, from, currentName);
            webhookService.fireB2bCard("🔔 B2B Ticket Reassigned", body, mention.email(), mention.name(), t);
            // New assignee context — let support / SLA warnings fire again.
            st.setSupportNotified("NONE");
            st.setSlaWarned(false);
        }
        st.setAccountId(currentAccId);
        st.setAssigneeName(currentName);

        // --- 2. Needs Matrixx / Aria support ---
        String supportType = "NONE";
        if (hasAssignee) {
            boolean matrixxMember = memberOpt.map(B2bMember::isMatrixxSupport).orElse(false);
            boolean ariaMember    = memberOpt.map(B2bMember::isAriaSupport).orElse(false);
            String escLower = escalation == null ? "" : escalation.toLowerCase();
            if (matrixxMember || escLower.contains("matrixx"))      supportType = "MATRIXX";
            else if (ariaMember || escLower.contains("aria"))       supportType = "ARIA";
        }
        if (hasAssignee && !supportType.equals(st.getSupportNotified())) {
            if (!supportType.equals("NONE")) {
                String label = supportType.equals("MATRIXX") ? "Matrixx" : "Aria";
                Map<String, String> t = ticketMap(issueKey, summary, "Assignee: " + currentName, slaFriendly);
                String nameTag = mention.has() ? "<at>" + mention.name() + "</at>" : mention.name();
                String body = "Hi " + nameTag + " — this B2B ticket needs " + label
                    + " support, please check this on priority.";
                log.info("[{}] {} needs {} support — notifying {}", team.getName(), issueKey, label, currentName);
                webhookService.fireB2bCard("🛠 B2B Ticket Needs " + label + " Support",
                    body, mention.email(), mention.name(), t);
            }
            st.setSupportNotified(supportType); // also covers silent reset to NONE
        }

        // --- 3. SLA breach warning (within 15 min, not yet breached) ---
        boolean inWarnWindow = hasAssignee && sla.available() && !sla.breached()
            && sla.remainingMillis() > 0 && sla.remainingMillis() <= SLA_WARN_WINDOW_MS;
        if (inWarnWindow && !st.isSlaWarned()) {
            Map<String, String> t = ticketMap(issueKey, summary, "Assignee: " + currentName, slaFriendly);
            String nameTag = mention.has() ? "<at>" + mention.name() + "</at>" : mention.name();
            String body = nameTag + " — heads up: this B2B ticket's SLA will breach within ~15 minutes.";
            log.info("[{}] {} SLA breach warning ({} left) — notifying {}",
                team.getName(), issueKey, slaFriendly, currentName);
            webhookService.fireB2bCard("⏰ B2B SLA Breach Warning", body, mention.email(), mention.name(), t);
            st.setSlaWarned(true);
        } else if (st.isSlaWarned() && sla.available() && !sla.breached()
                   && sla.remainingMillis() > SLA_WARN_WINDOW_MS) {
            // SLA pushed back out of the window (paused/extended) — allow a future warning.
            st.setSlaWarned(false);
        }

        st.setUpdatedAt(LocalDateTime.now());
        stateRepository.save(st);
    }

    /** Purges per-ticket dedupe state not touched in the given number of days. */
    public void purgeOldState(int days) {
        int removed = stateRepository.deleteByUpdatedAtBefore(LocalDateTime.now().minusDays(days));
        if (removed > 0) log.info("Purged {} B2B dedupe-state row(s) older than {} days.", removed, days);
    }

    /** Match a Jira assignee to a mapped member by email first, then by display name. */
    private Optional<B2bMember> resolveMember(String email, String displayName) {
        if (email != null && !email.isBlank()) {
            Optional<B2bMember> byEmail = memberRepository.findByJiraEmailIgnoreCase(email.trim());
            if (byEmail.isPresent()) return byEmail;
        }
        if (displayName != null && !displayName.isBlank()) {
            return memberRepository.findByJiraNameIgnoreCase(displayName.trim());
        }
        return Optional.empty();
    }

    /** Builds the @mention target: mapped Teams identity if present, else the plain Jira name. */
    private Mention mention(Optional<B2bMember> memberOpt, String fallbackName) {
        if (memberOpt.isPresent()) {
            B2bMember m = memberOpt.get();
            String email = m.getTeamsEmail();
            if (email != null && !email.isBlank()) {
                String name = (m.getTeamsName() != null && !m.getTeamsName().isBlank())
                    ? m.getTeamsName()
                    : (fallbackName == null || fallbackName.isBlank() ? email : fallbackName);
                return new Mention(email.trim(), name, true);
            }
        }
        return new Mention("", fallbackName == null ? "" : fallbackName, false);
    }

    private Map<String, String> ticketMap(String key, String summary, String context, String sla) {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("key",     key);
        t.put("url",     jiraClient.browseUrl(key));
        t.put("summary", summary);
        t.put("context", context);
        t.put("sla",     sla == null ? "" : sla);
        return t;
    }
}
