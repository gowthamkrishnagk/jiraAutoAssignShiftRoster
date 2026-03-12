package com.jira.autoassign.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.jira.autoassign.config.JiraProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class JiraClient {

    private final RestTemplate restTemplate;
    private final JiraProperties props;

    private HttpHeaders authHeaders() {
        String credentials = props.getEmail() + ":" + props.getApiToken();
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + encoded);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
        return headers;
    }

    public String getAccountId(String email) {
        String url = UriComponentsBuilder
                .fromHttpUrl(props.getUrl() + "/rest/api/3/user/search")
                .queryParam("query", email)
                .toUriString();

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(authHeaders()), JsonNode.class);

        JsonNode users = response.getBody();
        if (users == null || !users.isArray() || users.isEmpty()) {
            throw new RuntimeException("No Jira user found for email: " + email);
        }
        return users.get(0).get("accountId").asText();
    }

    public List<JsonNode> getTickets() {
        String jql;

        if (props.getCustomJql() != null && !props.getCustomJql().isBlank()) {
            // Use custom JQL directly — ignores all other filter properties
            jql = props.getCustomJql();
            log.info("Using custom JQL: {}", jql);
        } else {
            // Build JQL from individual filter properties
            List<String> conditions = new ArrayList<>();
            conditions.add("project = " + props.getProjectKey());

            if (props.isOnlyUnassigned()) {
                conditions.add("assignee is EMPTY");
            }

            if (props.getTargetStatuses() != null && !props.getTargetStatuses().isEmpty()) {
                String statusList = String.join(", ",
                        props.getTargetStatuses().stream().map(s -> "\"" + s + "\"").toList());
                conditions.add("status in (" + statusList + ")");
            }

            if (props.getTargetIssueTypes() != null && !props.getTargetIssueTypes().isEmpty()) {
                String typeList = String.join(", ",
                        props.getTargetIssueTypes().stream().map(t -> "\"" + t + "\"").toList());
                conditions.add("issuetype in (" + typeList + ")");
            }

            if (props.getTargetLabels() != null && !props.getTargetLabels().isEmpty()) {
                String labelList = String.join(", ",
                        props.getTargetLabels().stream().map(l -> "\"" + l + "\"").toList());
                conditions.add("labels in (" + labelList + ")");
            }

            jql = String.join(" AND ", conditions) + " ORDER BY created ASC";
            log.info("Using built JQL: {}", jql);
        }

        String url = UriComponentsBuilder
                .fromHttpUrl(props.getUrl() + "/rest/api/3/search")
                .queryParam("jql", jql)
                .queryParam("maxResults", 100)
                .queryParam("fields", "summary,assignee,status,issuetype,labels")
                .toUriString();

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(authHeaders()), JsonNode.class);

        List<JsonNode> tickets = new ArrayList<>();
        if (response.getBody() != null && response.getBody().has("issues")) {
            response.getBody().get("issues").forEach(tickets::add);
        }
        return tickets;
    }

    public boolean assignTicket(String issueKey, String accountId) {
        String url = props.getUrl() + "/rest/api/3/issue/" + issueKey + "/assignee";

        HttpEntity<Map<String, String>> request = new HttpEntity<>(
                Map.of("accountId", accountId), authHeaders());

        ResponseEntity<Void> response = restTemplate.exchange(
                url, HttpMethod.PUT, request, Void.class);

        return response.getStatusCode() == HttpStatus.NO_CONTENT;
    }
}
