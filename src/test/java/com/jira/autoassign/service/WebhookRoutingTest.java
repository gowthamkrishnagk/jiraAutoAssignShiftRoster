package com.jira.autoassign.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebhookRoutingTest {
    private final BlockingQueue<Delivery> deliveries = new LinkedBlockingQueue<>();
    private final TeamWebhookService destinations = mock(TeamWebhookService.class);
    private final JiraConfigService config = mock(JiraConfigService.class);
    private HttpServer server;
    private WebhookService service;
    private String base;
    private record Delivery(String path, JsonNode payload) { }

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode payload = new ObjectMapper().readTree(exchange.getRequestBody());
            Delivery delivery = new Delivery(exchange.getRequestURI().getPath(), payload);
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            deliveries.add(delivery);
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        when(config.getUrl()).thenReturn("https://jira.example");
        when(destinations.getUrl("first")).thenReturn(base + "/first");
        when(destinations.getUrl("second")).thenReturn(base + "/second");
        service = new WebhookService(config, destinations);
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    @Test
    void handoverAlertsReachTheirOwnTeamsDestination() throws Exception {
        service.fireReassignments("first", "First", List.of(ticket("SAC-1")));
        Delivery first = next();
        assertEquals("/first", first.path());
        assertEquals("First", first.payload().path("teamName").asText());
        assertEquals("SAC-1", first.payload().path("tickets").get(0).path("key").asText());
        service.fireReassignments("second", "Second", List.of(ticket("SAC-2")));
        assertEquals("/second", next().path());
        verify(config, never()).getWebhookUrl();
    }

    @Test
    void monitorAlertsAlsoUseTheOriginatingTeam() throws Exception {
        service.fireB2bCard("second", "Alert", "Body", "Message", "", "", ticket("SAC-3"));
        Delivery delivery = next();
        assertEquals("/second", delivery.path());
        assertEquals("message", delivery.payload().path("type").asText());
        verify(config, never()).getB2bWebhookUrl();
    }

    @Test
    void disabledTeamsSendNothing() throws Exception {
        when(destinations.getUrl("disabled")).thenReturn("");
        service.fireReassignments("disabled", "Disabled", List.of(ticket("SAC-4")));
        service.fireB2bCard("disabled", "Alert", "Body", "Message", "", "", ticket("SAC-4"));
        assertNull(deliveries.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    void testCardIdentifiesTheSelectedTeam() throws Exception {
        assertEquals(202, service.testWebhook(base + "/second", "second", "Second"));
        Delivery delivery = next();
        assertEquals("/second", delivery.path());
        assertEquals("Second", delivery.payload().path("teamName").asText());
        assertTrue(delivery.payload().path("adaptiveCard").asText().contains("Second"));
    }

    private Delivery next() throws Exception {
        Delivery delivery = deliveries.poll(5, TimeUnit.SECONDS);
        assertNotNull(delivery, "Expected an HTTP webhook delivery to the local test server");
        return delivery;
    }

    private Map<String, String> ticket(String key) {
        return Map.of("key", key, "summary", "Sample", "from", "old@example.com",
            "to", "new@example.com", "slaRemaining", "1h", "url", "https://jira.example/browse/" + key);
    }
}
