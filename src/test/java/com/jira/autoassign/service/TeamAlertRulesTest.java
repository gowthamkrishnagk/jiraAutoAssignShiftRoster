package com.jira.autoassign.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jira.autoassign.client.JiraClient;
import com.jira.autoassign.entity.B2bAssignState;
import com.jira.autoassign.entity.Team;
import com.jira.autoassign.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.*;

class TeamAlertRulesTest {
    private final JiraClient jira = mock(JiraClient.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final B2bAssignStateRepository states = mock(B2bAssignStateRepository.class);
    private final WebhookService sender = mock(WebhookService.class);
    private final JiraConfigService config = mock(JiraConfigService.class);
    private final Map<String, B2bAssignState> saved = new HashMap<>();
    private final B2bNotifyService service = new B2bNotifyService(jira, teams, mock(B2bMemberRepository.class),
        states, sender, config, mock(B2bNotifyLogRepository.class));
    private JsonNode ticket;

    @BeforeEach
    void setUp() throws Exception {
        ticket = new ObjectMapper().readTree("{\"key\":\"SAC-1\",\"fields\":{\"summary\":\"Sample\","
            + "\"assignee\":{\"accountId\":\"new\",\"emailAddress\":\"new@example.com\",\"displayName\":\"New User\"}}}");
        when(jira.extractSlaOngoing(any())).thenReturn(new JiraClient.SlaOngoing(true, true, -1));
        when(jira.extractSlaRemaining(any())).thenReturn("Breached");
        when(jira.browseUrl("SAC-1")).thenReturn("https://jira.example/browse/SAC-1");
        when(config.getB2bTeamsDomain()).thenReturn("");
        when(states.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(saved.get(inv.getArgument(0))));
        when(states.save(any())).thenAnswer(inv -> {
            B2bAssignState state = inv.getArgument(0); saved.put(state.getIssueKey(), state); return state;
        });
    }

    @Test
    void slaAlertsWorkForAutoAssignTeamsAndAreDedupedPerTeam() {
        Team first = team("first", true), second = team("second", true);
        first.setSlaAlerts(true); second.setSlaAlerts(true);
        when(teams.findAll()).thenReturn(List.of(first, second));
        when(jira.getTeamAlertTickets(anyString())).thenReturn(List.of(ticket));
        service.runAll(); service.runAll();
        verify(sender).fireB2bCard(eq("first"), contains("SLA Breached"), anyString(), anyString(), anyString(), anyString(), anyMap());
        verify(sender).fireB2bCard(eq("second"), contains("SLA Breached"), anyString(), anyString(), anyString(), anyString(), anyMap());
        verifyNoMoreInteractions(sender);
    }

    @Test
    void disabledAutoTeamSlaDoesNotFetchOrSend() {
        Team first = team("first", true);
        first.setSlaAlerts(false);
        when(teams.findAll()).thenReturn(List.of(first));
        service.runAll();
        verifyNoInteractions(jira, sender);
    }

    @Test
    void monitorAssignmentAndReassignmentRulesAreIndependent() {
        Team monitor = team("b2b", false);
        monitor.setSlaAlerts(false); monitor.setAssignmentAlerts(false); monitor.setReassignmentAlerts(true);
        B2bAssignState prior = new B2bAssignState("SAC-1");
        prior.setAccountId(""); saved.put("SAC-1", prior);
        when(teams.findAll()).thenReturn(List.of(monitor));
        when(jira.getB2bTickets(anyString())).thenReturn(List.of(ticket));
        service.runAll();
        verifyNoInteractions(sender);

        prior.setAccountId(""); monitor.setAssignmentAlerts(true);
        service.runAll();
        verify(sender).fireB2bCard(eq("b2b"), contains("Ticket Assigned"), anyString(), anyString(), anyString(), anyString(), anyMap());
        clearInvocations(sender);
        prior.setAccountId("old"); monitor.setReassignmentAlerts(false);
        service.runAll();
        verifyNoInteractions(sender);
    }

    private Team team(String id, boolean auto) {
        Team team = new Team(id, id, "project = SAC", auto);
        team.setWebhookUrl("https://" + id + ".example/hook");
        return team;
    }
}
