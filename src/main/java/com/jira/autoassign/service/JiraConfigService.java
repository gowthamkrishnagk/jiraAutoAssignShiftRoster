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
    private volatile String email;
    private volatile String apiToken;
    private volatile String slaFieldId;
    private volatile String webhookUrl;
    private volatile String b2bWebhookUrl;

    public JiraConfigService(JiraConfigRepository repo, JiraProperties props) {
        this.repo  = repo;
        this.props = props;
    }

    @PostConstruct
    public void init() {
        JiraConfig saved = repo.findById(1L).orElse(null);
        if (saved != null && saved.getJiraEmail() != null && !saved.getJiraEmail().isBlank()) {
            email    = saved.getJiraEmail();
            apiToken = saved.getApiToken();
        } else {
            // Fall back to application.properties
            email    = props.getEmail();
            apiToken = props.getApiToken();
        }
        slaFieldId = (saved != null && saved.getSlaFieldId() != null)
                     ? saved.getSlaFieldId() : "";
        webhookUrl = (saved != null && saved.getWebhookUrl() != null)
                     ? saved.getWebhookUrl() : "";
        b2bWebhookUrl = (saved != null && saved.getB2bWebhookUrl() != null)
                     ? saved.getB2bWebhookUrl() : "";
    }

    public String getUrl()        { return props.getUrl(); } // always from application.properties
    public String getEmail()      { return email; }
    public String getApiToken()   { return apiToken; }
    public String getSlaFieldId() { return slaFieldId != null ? slaFieldId : ""; }
    public String getWebhookUrl() { return webhookUrl != null ? webhookUrl : ""; }
    public String getB2bWebhookUrl() { return b2bWebhookUrl != null ? b2bWebhookUrl : ""; }

    public boolean isConfigured() {
        return email != null && !email.isBlank()
            && apiToken != null && !apiToken.isBlank();
    }

    public void save(String jiraEmail, String token) {
        JiraConfig cfg = repo.findById(1L).orElse(new JiraConfig());
        cfg.setJiraEmail(jiraEmail.trim());
        cfg.setApiToken(token.trim());
        repo.save(cfg);

        // Update live values immediately — no restart needed
        this.email    = jiraEmail.trim();
        this.apiToken = token.trim();
    }

    public void saveSlaFieldId(String fieldId) {
        JiraConfig cfg = repo.findById(1L).orElse(new JiraConfig());
        cfg.setSlaFieldId(fieldId.trim());
        repo.save(cfg);
        this.slaFieldId = fieldId.trim();
    }

    public void saveWebhookUrl(String url) {
        JiraConfig cfg = repo.findById(1L).orElse(new JiraConfig());
        cfg.setWebhookUrl(url.trim());
        repo.save(cfg);
        this.webhookUrl = url.trim();
    }

    public void saveB2bWebhookUrl(String url) {
        JiraConfig cfg = repo.findById(1L).orElse(new JiraConfig());
        cfg.setB2bWebhookUrl(url.trim());
        repo.save(cfg);
        this.b2bWebhookUrl = url.trim();
    }
}
