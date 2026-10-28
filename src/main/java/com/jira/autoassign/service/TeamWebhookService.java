package com.jira.autoassign.service;

import com.jira.autoassign.entity.Team;
import com.jira.autoassign.repository.TeamRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

/** Stores independent alert destinations; legacy global settings are copied once. */
@Service
public class TeamWebhookService {
    private final TeamRepository teams;
    private final JiraConfigService legacyConfig;

    public TeamWebhookService(TeamRepository teams, JiraConfigService legacyConfig) {
        this.teams = teams;
        this.legacyConfig = legacyConfig;
    }

    @PostConstruct
    public void migrateLegacySettings() {
        for (Team team : teams.findAll()) {
            if (team.getWebhookUrl() != null) continue;
            String url = team.isAutoAssign() ? legacyConfig.getWebhookUrl()
                : legacyConfig.getB2bWebhookUrl();
            team.setWebhookUrl(url == null ? "" : url.trim());
            teams.save(team);
        }
    }

    public Team requireTeam(String teamId) {
        return teams.findById(teamId).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));
    }

    public String getUrl(String teamId) {
        return teams.findById(teamId).map(Team::getWebhookUrl).orElse("");
    }

    public String saveUrl(String teamId, String value) {
        return saveSettings(teamId, value == null ? "" : value, null, null, null).getWebhookUrl();
    }

    public Team saveSettings(String teamId, String value, Boolean assignment, Boolean reassignment, Boolean sla,
                             Boolean slaWarning, Boolean slaBreach,
                             Boolean slaWarning30m, Boolean slaWarning15m) {
        Team team = requireTeam(teamId);
        if (value != null) team.setWebhookUrl(validateUrl(value));
        if (assignment != null) team.setAssignmentAlerts(assignment);
        if (reassignment != null) team.setReassignmentAlerts(reassignment);
        if (sla != null) team.setSlaAlerts(sla);
        if (slaWarning != null) team.setSlaWarningAlerts(slaWarning);
        if (slaBreach != null) team.setSlaBreachAlerts(slaBreach);
        if (slaWarning30m != null) team.setSlaWarning30mAlerts(slaWarning30m);
        if (slaWarning15m != null) team.setSlaWarning15mAlerts(slaWarning15m);
        teams.save(team);
        return team;
    }

    /** Compatibility overload for clients that submit one SLA warning setting and one breach setting. */
    public Team saveSettings(String teamId, String value, Boolean assignment, Boolean reassignment, Boolean sla,
                             Boolean slaWarning, Boolean slaBreach) {
        return saveSettings(teamId, value, assignment, reassignment, sla, slaWarning, slaBreach, null, null);
    }

    /** Compatibility overload for older clients that submit one combined SLA alert setting. */
    public Team saveSettings(String teamId, String value, Boolean assignment, Boolean reassignment, Boolean sla) {
        return saveSettings(teamId, value, assignment, reassignment, sla, null, null, null, null);
    }

    public boolean assignmentEnabled(String teamId) {
        return teams.findById(teamId).map(Team::isAssignmentAlerts).orElse(false);
    }

    public boolean reassignmentEnabled(String teamId) {
        return teams.findById(teamId).map(Team::isReassignmentAlerts).orElse(false);
    }

    public String testUrl(String teamId, String value) {
        Team team = requireTeam(teamId);
        String url = value == null || value.isBlank() ? team.getWebhookUrl() : value;
        url = validateUrl(url);
        if (url.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No webhook URL configured for this team");
        return url;
    }

    /** Validates a destination before a new team or a settings change is saved. */
    public String validateUrl(String value) {
        String url = value == null ? "" : value.trim();
        if (url.isEmpty()) return url;
        try {
            URI uri = URI.create(url);
            if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null) return url;
        } catch (IllegalArgumentException ignored) { }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid HTTP or HTTPS webhook URL");
    }

    public static String maskUrl(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            URI uri = URI.create(url);
            return uri.getScheme() + "://" + uri.getHost() + "/" + "\u2022".repeat(12);
        } catch (IllegalArgumentException e) {
            return "Configured";
        }
    }
}
