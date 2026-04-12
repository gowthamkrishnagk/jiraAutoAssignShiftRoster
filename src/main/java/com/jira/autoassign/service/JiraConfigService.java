package com.jira.autoassign.service;

import com.jira.autoassign.config.JiraProperties;
import com.jira.autoassign.entity.JiraConfig;
import com.jira.autoassign.repository.JiraConfigRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

/**
 * Single source of truth for Jira connection settings.
 * DB values take priority over application.properties — so UI changes take effect immediately.
 */
@Service
public class JiraConfigService {

    private final JiraConfigRepository repo;
    private final JiraProperties props;

    // Live in-memory values — updated whenever user saves from UI
    private volatile String url;
    private volatile String email;
    private volatile String apiToken;

    public JiraConfigService(JiraConfigRepository repo, JiraProperties props) {
        this.repo  = repo;
        this.props = props;
    }

    @PostConstruct
    public void init() {
        JiraConfig saved = repo.findById(1L).orElse(null);
        if (saved != null && saved.getJiraUrl() != null && !saved.getJiraUrl().isBlank()) {
            url      = saved.getJiraUrl();
            email    = saved.getJiraEmail();
            apiToken = saved.getApiToken();
        } else {
            // Fall back to application.properties
            url      = props.getUrl();
            email    = props.getEmail();
            apiToken = props.getApiToken();
        }
    }

    public String getUrl()      { return url; }
    public String getEmail()    { return email; }
    public String getApiToken() { return apiToken; }

    public boolean isConfigured() {
        return url != null && !url.isBlank()
            && email != null && !email.isBlank()
            && apiToken != null && !apiToken.isBlank();
    }

    public void save(String jiraUrl, String jiraEmail, String token) {
        JiraConfig cfg = repo.findById(1L).orElse(new JiraConfig());
        cfg.setJiraUrl(jiraUrl.trim());
        cfg.setJiraEmail(jiraEmail.trim());
        cfg.setApiToken(token.trim());
        repo.save(cfg);

        // Update live values immediately — no restart needed
        this.url      = jiraUrl.trim();
        this.email    = jiraEmail.trim();
        this.apiToken = token.trim();
    }
}
