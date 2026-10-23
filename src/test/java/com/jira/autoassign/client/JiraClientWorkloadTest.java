package com.jira.autoassign.client;

import com.jira.autoassign.config.JiraProperties;
import com.jira.autoassign.service.JiraConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class JiraClientWorkloadTest {
    private MockRestServiceServer server;
    private JiraClient client;

    @BeforeEach
    void setUp() {
        RestTemplate rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        JiraConfigService config = mock(JiraConfigService.class);
        when(config.getUrl()).thenReturn("https://jira.example");
        when(config.getEmail()).thenReturn("test@example.com");
        when(config.getApiToken()).thenReturn("test-token");
        client = new JiraClient(rest, new JiraProperties(), config);
    }

    @Test
    void oneBulkQueryCountsAllPagesAndKeepsZeroTicketMembers() {
        server.expect(request -> {
            String query = URLDecoder.decode(request.getURI().getRawQuery(), StandardCharsets.UTF_8);
            assertTrue(query.contains("assignee IN ("));
            assertTrue(query.contains("\"a\""));
            assertTrue(query.contains("\"b\""));
            assertTrue(query.contains("\"zero\""));
            assertFalse(query.contains("status"));
            assertTrue(query.contains("fields=assignee"));
        }).andRespond(withSuccess("{\"issues\":[{\"fields\":{\"assignee\":{\"accountId\":\"a\"}}}],\"nextPageToken\":\"next\"}", MediaType.APPLICATION_JSON));
        server.expect(request -> assertTrue(request.getURI().getRawQuery().contains("nextPageToken=next")))
            .andRespond(withSuccess("{\"issues\":[{\"fields\":{\"assignee\":{\"accountId\":\"a\"}}},{\"fields\":{\"assignee\":{\"accountId\":\"b\"}}}],\"isLast\":true}", MediaType.APPLICATION_JSON));
        assertEquals(Map.of("a", 2, "b", 1, "zero", 0), client.getWorkloadCounts(
            List.of("a", "b", "zero"), "project = SAC AND status in (Open) AND assignee in (EMPTY)"));
        server.verify();
    }

    @Test
    void emailLookupSelectsTheExactActiveMatchInsteadOfTheFirstResult() {
        server.expect(request -> {}).andRespond(withSuccess("["
            + "{\"accountId\":\"wrong\",\"emailAddress\":\"other@example.com\",\"active\":true},"
            + "{\"accountId\":\"right\",\"emailAddress\":\"PERSON@example.com\",\"active\":true}]", MediaType.APPLICATION_JSON));
        assertEquals("right", client.getAccountId("person@example.com"));
        server.verify();
    }

    @Test
    void ambiguousHiddenEmailsAreRejected() {
        server.expect(request -> {}).andRespond(withSuccess("["
            + "{\"accountId\":\"one\",\"active\":true},"
            + "{\"accountId\":\"two\",\"active\":true}]", MediaType.APPLICATION_JSON));
        assertThrows(IllegalStateException.class, () -> client.getAccountId("person@example.com"));
        server.verify();
    }

    @Test
    void aUniqueActiveResultWorksWhenJiraHidesTheEmail() {
        server.expect(request -> {}).andRespond(withSuccess("[{\"accountId\":\"one\",\"active\":true}]", MediaType.APPLICATION_JSON));
        assertEquals("one", client.getAccountId("person@example.com"));
        server.verify();
    }
}
