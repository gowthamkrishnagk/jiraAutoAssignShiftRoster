package com.jira.autoassign.controller;

import com.jira.autoassign.entity.Team;
import com.jira.autoassign.repository.TeamRepository;
import com.jira.autoassign.repository.B2bMemberRepository;
import com.jira.autoassign.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TeamWebhookControllerTest {
    private final TeamRepository teams = mock(TeamRepository.class);
    private final JiraConfigService config = mock(JiraConfigService.class);
    private final TeamWebhookService settings = new TeamWebhookService(teams, config);
    private final WebhookService sender = mock(WebhookService.class);
    private final Team first = new Team("first", "First", "jql");
    private final Team monitor = new Team("b2b", "B2B", "jql", false);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(teams.findById("first")).thenReturn(Optional.of(first));
        when(teams.findById("b2b")).thenReturn(Optional.of(monitor));
        B2bMemberRepository members = mock(B2bMemberRepository.class);
        when(members.findAll()).thenReturn(List.of());
        when(config.getB2bTeamsDomain()).thenReturn("");
        mvc = MockMvcBuilders.standaloneSetup(
            new UploadController(null, null, null, teams, config, sender, null, settings),
            new B2bController(members, config, sender, null, settings)).build();
    }

    @Test
    void teamIsRequiredAndUnknownTeamsReturn404() throws Exception {
        mvc.perform(get("/api/webhook-settings")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/webhook-settings").param("team", "missing"))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/webhook-settings").param("team", "missing")
            .contentType("application/json").content("{\"webhookUrl\":\"https://example.com/hook\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void savesAreScopedAndResponsesHideTheSecret() throws Exception {
        mvc.perform(post("/api/webhook-settings").param("team", "first")
            .contentType("application/json").content("{\"webhookUrl\":\"https://example.com/secret?sig=private\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.teamId").value("first"))
            .andExpect(jsonPath("$.webhookUrl").doesNotExist());
        mvc.perform(get("/api/webhook-settings").param("team", "first"))
            .andExpect(jsonPath("$.configured").value(true))
            .andExpect(jsonPath("$.webhookUrlMasked").value(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("private"))));
        mvc.perform(get("/api/webhook-settings").param("team", "b2b"))
            .andExpect(jsonPath("$.configured").value(false));
        mvc.perform(post("/api/webhook-settings").param("team", "first")
            .contentType("application/json").content("{\"webhookUrl\":\"\"}"))
            .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void testsUseSelectedTeamsUrlAndCardFormat() throws Exception {
        first.setWebhookUrl("https://first.example/hook");
        monitor.setWebhookUrl("https://b2b.example/hook");
        when(sender.testWebhook("https://first.example/hook", "first", "First")).thenReturn(202);
        when(sender.testB2bWebhook("https://b2b.example/hook", "", "")).thenReturn(200);
        mvc.perform(post("/api/webhook-settings/test").param("team", "first")
            .contentType("application/json").content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(post("/api/webhook-settings/test").param("team", "b2b")
            .contentType("application/json").content("{}"))
            .andExpect(status().isOk());
        verify(sender).testWebhook("https://first.example/hook", "first", "First");
        verify(sender).testB2bWebhook("https://b2b.example/hook", "", "");
    }

    @Test
    void b2bPanelSharesTheSameSavedDestination() throws Exception {
        mvc.perform(post("/api/b2b/webhook-settings").contentType("application/json")
            .content("{\"webhookUrl\":\"https://b2b.example/hook\"}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/webhook-settings").param("team", "b2b"))
            .andExpect(jsonPath("$.configured").value(true));
        when(sender.testB2bWebhook("https://b2b.example/hook", "", "")).thenReturn(200);
        mvc.perform(post("/api/b2b/webhook-settings/test").contentType("application/json").content("{}"))
            .andExpect(status().isOk());
        verify(sender).testB2bWebhook("https://b2b.example/hook", "", "");
        verify(config, never()).saveB2bWebhookUrl(any());
    }

    @Test
    void disabledOrInvalidDestinationsAreRejected() throws Exception {
        mvc.perform(post("/api/webhook-settings/test").param("team", "first")
            .contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/webhook-settings").param("team", "first")
            .contentType("application/json").content("{\"webhookUrl\":\"invalid\"}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(sender);
    }

    @Test
    void newTeamsSaveTheirOwnWebhookAndReceiveDistinctIds() throws Exception {
        mvc.perform(post("/api/teams").contentType("application/json")
            .content("{\"name\":\"Support\",\"jql\":\"project = SUP\",\"webhookUrl\":\"https://support.example/hook\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.webhookConfigured").value(true))
            .andExpect(jsonPath("$.webhookUrl").doesNotExist());
        mvc.perform(post("/api/teams").contentType("application/json")
            .content("{\"name\":\"Operations\",\"jql\":\"project = OPS\",\"webhookUrl\":\"https://ops.example/hook\"}"))
            .andExpect(status().isOk());
        ArgumentCaptor<Team> saved = ArgumentCaptor.forClass(Team.class);
        verify(teams, times(2)).save(saved.capture());
        Team support = saved.getAllValues().get(0);
        Team operations = saved.getAllValues().get(1);
        assertNotEquals(support.getId(), operations.getId());
        assertNotNull(UUID.fromString(support.getId()));
        assertEquals("https://support.example/hook", support.getWebhookUrl());
        assertEquals("https://ops.example/hook", operations.getWebhookUrl());
    }

    @Test
    void newTeamCanReceiveItsWebhookAfterCreation() throws Exception {
        mvc.perform(post("/api/teams").contentType("application/json")
            .content("{\"name\":\"Support\",\"jql\":\"project = SUP\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.webhookConfigured").value(false));
        ArgumentCaptor<Team> saved = ArgumentCaptor.forClass(Team.class);
        verify(teams).save(saved.capture());
        Team team = saved.getValue();
        assertEquals("", team.getWebhookUrl());
        when(teams.findById(team.getId())).thenReturn(Optional.of(team));
        mvc.perform(post("/api/webhook-settings").param("team", team.getId())
            .contentType("application/json").content("{\"webhookUrl\":\"https://support.example/hook\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(true));
        assertEquals("https://support.example/hook", team.getWebhookUrl());
        assertEquals("", first.getWebhookUrl());
    }

    @Test
    void invalidWebhookPreventsTeamCreation() throws Exception {
        mvc.perform(post("/api/teams").contentType("application/json")
            .content("{\"name\":\"Support\",\"jql\":\"project = SUP\",\"webhookUrl\":\"invalid\"}"))
            .andExpect(status().isBadRequest());
        verify(teams, never()).save(any());
    }
}
