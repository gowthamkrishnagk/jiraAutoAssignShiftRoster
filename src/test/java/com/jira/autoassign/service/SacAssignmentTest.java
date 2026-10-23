package com.jira.autoassign.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jira.autoassign.client.JiraClient;
import com.jira.autoassign.entity.ShiftRoster;
import com.jira.autoassign.entity.Team;
import com.jira.autoassign.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SacAssignmentTest {
    private final JiraClient jira = mock(JiraClient.class);
    private final ShiftRosterRepository roster = mock(ShiftRosterRepository.class);
    private final WebhookService webhook = mock(WebhookService.class);
    private final ShiftAssignService service = new ShiftAssignService(jira, roster,
        mock(AssignmentLogRepository.class), mock(TeamRepository.class), webhook,
        mock(CoverAssignmentRepository.class), mock(RosterEventRepository.class));
    private final Team sac = new Team("sac", "SAC", "project = SAC AND status in (Open) AND assignee in (EMPTY)");

    @BeforeEach
    void setUp() throws Exception {
        ShiftRoster first = shift("a@example.com");
        ShiftRoster second = shift("b@example.com");
        when(roster.findActiveShifts(eq("sac"), any(), any(), any())).thenReturn(List.of(first, second));
        when(jira.getAccountId("a@example.com")).thenReturn("a");
        when(jira.getAccountId("b@example.com")).thenReturn("b");
        when(jira.getWorkloadCounts(anyCollection(), eq(sac.getJql()))).thenReturn(Map.of("a", 1, "b", 4));
        when(jira.assignTicket(anyString(), anyString())).thenReturn(true);
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> tickets = List.of(
            mapper.readTree("{\"key\":\"SAC-1\",\"fields\":{\"assignee\":null,\"summary\":\"One\"}}"),
            mapper.readTree("{\"key\":\"SAC-2\",\"fields\":{\"assignee\":null,\"summary\":\"Two\"}}"),
            mapper.readTree("{\"key\":\"SAC-3\",\"fields\":{\"assignee\":null,\"summary\":\"Three\"}}"),
            mapper.readTree("{\"key\":\"SAC-4\",\"fields\":{\"assignee\":null,\"summary\":\"Four\"}}"));
        when(jira.getTicketsByJql(sac.getJql())).thenReturn(tickets);
    }

    @Test
    void oneBulkCountQueryDrivesLowestWorkloadAssignments() {
        service.runShiftAssignment(sac);
        ArgumentCaptor<String> recipient = ArgumentCaptor.forClass(String.class);
        verify(jira, times(4)).assignTicket(anyString(), recipient.capture());
        assertEquals(List.of("a", "a", "a", "b"), recipient.getAllValues());
        verify(jira).getWorkloadCounts(List.of("a", "b"), sac.getJql());
        verify(webhook).fireAssignments(eq("sac"), eq("SAC"), argThat(t -> t.size() == 4));
    }

    @Test
    void usesTheSeparateCountJqlAndTheReceivingCoverAccount() {
        sac.setWorkloadJql("project = SAC");
        ShiftRoster covered = shift("a@example.com");
        covered.setCoverEmail("cover@example.com");
        when(roster.findActiveShifts(eq("sac"), any(), any(), any())).thenReturn(List.of(covered));
        when(jira.getAccountId("cover@example.com")).thenReturn("cover-account");
        when(jira.getWorkloadCounts(anyCollection(), eq("project = SAC"))).thenReturn(Map.of("cover-account", 2));
        service.runShiftAssignment(sac);
        verify(jira).getWorkloadCounts(List.of("cover-account"), "project = SAC");
        verify(jira, times(4)).assignTicket(anyString(), eq("cover-account"));
        verify(jira, never()).getAccountId("a@example.com");
    }

    @Test
    void otherTeamsKeepTheirRoundRobinPolicy() {
        Team other = new Team("other", "Other", sac.getJql());
        when(roster.findActiveShifts(eq("other"), any(), any(), any()))
            .thenReturn(List.of(shift("a@example.com"), shift("b@example.com")));
        service.runShiftAssignment(other);
        ArgumentCaptor<String> recipient = ArgumentCaptor.forClass(String.class);
        verify(jira, times(4)).assignTicket(anyString(), recipient.capture());
        assertEquals(List.of("a", "b", "a", "b"), recipient.getAllValues());
        verify(jira, never()).getWorkloadCounts(anyCollection(), anyString());
    }

    @Test
    void aFailedCountQueryDoesNotAssignUsingUnknownCounts() {
        when(jira.getWorkloadCounts(anyCollection(), anyString())).thenThrow(new RuntimeException("Jira unavailable"));
        assertThrows(IllegalStateException.class, () -> service.runShiftAssignment(sac));
        verify(jira, never()).assignTicket(anyString(), anyString());
    }

    private ShiftRoster shift(String email) {
        ShiftRoster row = new ShiftRoster();
        row.setTeamId("sac"); row.setEmail(email);
        return row;
    }
}
