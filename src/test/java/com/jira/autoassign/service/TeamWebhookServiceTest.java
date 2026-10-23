package com.jira.autoassign.service;

import com.jira.autoassign.entity.Team;
import com.jira.autoassign.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeamWebhookServiceTest {
    private final TeamRepository teams = mock(TeamRepository.class);
    private final JiraConfigService config = mock(JiraConfigService.class);
    private final TeamWebhookService service = new TeamWebhookService(teams, config);
    private final Team first = new Team("first", "First", "jql");
    private final Team second = new Team("second", "Second", "jql");

    @BeforeEach
    void setUp() {
        when(teams.findById("first")).thenReturn(Optional.of(first));
        when(teams.findById("second")).thenReturn(Optional.of(second));
    }

    @Test
    void changingOrClearingOneTeamDoesNotAffectAnother() {
        service.saveUrl("first", " https://first.example/secret ");
        service.saveUrl("second", "https://second.example/secret");
        assertEquals("https://first.example/secret", service.getUrl("first"));
        assertEquals("https://second.example/secret", service.getUrl("second"));
        service.saveUrl("first", "");
        assertEquals("", service.getUrl("first"));
        assertEquals("https://second.example/secret", service.getUrl("second"));
        verify(teams, times(2)).save(first);
        verify(teams).save(second);
    }

    @Test
    void legacySettingsAreCopiedOnlyToUnmigratedRows() {
        Team monitor = new Team("b2b", "B2B", "jql", false);
        first.setWebhookUrl(null);
        monitor.setWebhookUrl(null);
        when(teams.findAll()).thenReturn(List.of(first, second, monitor));
        when(config.getWebhookUrl()).thenReturn("https://legacy.example/secret");
        when(config.getB2bWebhookUrl()).thenReturn("https://monitor.example/secret");
        service.migrateLegacySettings();
        assertEquals("https://legacy.example/secret", first.getWebhookUrl());
        assertEquals("https://monitor.example/secret", monitor.getWebhookUrl());
        assertEquals("", second.getWebhookUrl());
        verify(teams, never()).save(second);

        service.saveUrl("first", "");
        service.migrateLegacySettings();
        assertEquals("", first.getWebhookUrl(), "Restart must preserve explicitly disabled alerts");
        verify(teams, times(2)).save(first);
        verify(teams).save(monitor);
    }

    @Test
    void testUsesOnlyTheSelectedTeamsSavedUrlOrAnUnsavedOverride() {
        service.saveUrl("first", "https://first.example/saved");
        assertEquals("https://first.example/saved", service.testUrl("first", ""));
        assertEquals("https://preview.example/url", service.testUrl("first", "https://preview.example/url"));
        assertEquals("https://first.example/saved", service.getUrl("first"));
        assertEquals(400, assertThrows(ResponseStatusException.class,
            () -> service.testUrl("second", "")).getStatusCode().value());
    }

    @Test
    void unknownTeamIsRejectedAndNeverUsesTheGlobalUrl() {
        assertEquals(404, assertThrows(ResponseStatusException.class,
            () -> service.saveUrl("missing", "https://example.com/secret")).getStatusCode().value());
        assertEquals("", service.getUrl("missing"));
        verifyNoInteractions(config);
    }

    @Test
    void invalidUrlsAreRejectedWithoutChangingTheSavedDestination() {
        first.setWebhookUrl("https://first.example/saved");
        for (String url : List.of("garbage", "file:///secret", "https://", "https://user:pass@host/path")) {
            assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.saveUrl("first", url)).getStatusCode().value());
        }
        assertEquals("https://first.example/saved", first.getWebhookUrl());
        verify(teams, never()).save(any());
    }

    @Test
    void previewDoesNotExposeWebhookPathOrSignature() {
        String masked = TeamWebhookService.maskUrl("https://example.com/secret/path?sig=private");
        assertTrue(masked.startsWith("https://example.com/"));
        assertFalse(masked.contains("secret"));
        assertFalse(masked.contains("private"));
        assertEquals("", TeamWebhookService.maskUrl(null));
    }
}
