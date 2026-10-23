package com.jira.autoassign.service;

import com.jira.autoassign.entity.Team;
import com.jira.autoassign.controller.UploadController;
import com.jira.autoassign.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false)
@ContextConfiguration(classes = TeamWebhookPersistenceTest.DatabaseConfig.class)
class TeamWebhookPersistenceTest {
    @Configuration
    @EntityScan(basePackageClasses = Team.class)
    @EnableJpaRepositories(basePackageClasses = TeamRepository.class)
    static class DatabaseConfig { }

    @Autowired TeamRepository teams;
    @Autowired EntityManager entityManager;

    @Test
    void migratedDestinationsAndExplicitlyDisabledAlertsSurviveReload() {
        Team legacy = new Team("legacy", "Legacy", "jql");
        legacy.setWebhookUrl(null);
        teams.saveAndFlush(legacy);
        entityManager.clear();
        assertNull(teams.findById("legacy").orElseThrow().getWebhookUrl());

        JiraConfigService config = mock(JiraConfigService.class);
        when(config.getWebhookUrl()).thenReturn("https://legacy.example/hook");
        TeamWebhookService settings = new TeamWebhookService(teams, config);
        settings.migrateLegacySettings();
        teams.flush();
        entityManager.clear();
        assertEquals("https://legacy.example/hook", settings.getUrl("legacy"));

        settings.saveUrl("legacy", "");
        teams.flush();
        entityManager.clear();
        new TeamWebhookService(teams, config).migrateLegacySettings();
        assertEquals("", settings.getUrl("legacy"));

        teams.saveAndFlush(new Team("new", "New", "jql"));
        entityManager.clear();
        assertEquals("", settings.getUrl("new"));
    }

    @Test
    void teamFormPersistsSeparateWebhooksAndAllowsAddingOneLater() {
        TeamWebhookService settings = new TeamWebhookService(teams, mock(JiraConfigService.class));
        UploadController controller = new UploadController(null, null, null, teams, null, null, null, settings);
        Map<?, ?> first = (Map<?, ?>) controller.addTeam(Map.of("name", "First", "jql", "project = ONE",
            "webhookUrl", "https://first.example/hook")).getBody();
        Map<?, ?> second = (Map<?, ?>) controller.addTeam(Map.of("name", "Second", "jql", "project = TWO")).getBody();
        String firstId = first.get("id").toString();
        String secondId = second.get("id").toString();
        teams.flush();
        entityManager.clear();
        assertNotEquals(firstId, secondId);
        assertEquals("https://first.example/hook", settings.getUrl(firstId));
        assertEquals("", settings.getUrl(secondId));

        settings.saveUrl(secondId, "https://second.example/hook");
        teams.flush();
        entityManager.clear();
        assertEquals("https://first.example/hook", settings.getUrl(firstId));
        assertEquals("https://second.example/hook", settings.getUrl(secondId));
    }
}
