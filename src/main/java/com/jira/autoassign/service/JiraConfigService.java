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
    private volatile String  b2bWebhookUrl;
    private volatile String  b2bTeamsDomain;
    private volatile String  slaReportRecipients;
    private volatile boolean slaReportEnabled = true;
    private volatile String   smtpHost;
    private volatile Integer  smtpPort;
    private volatile String   smtpUsername;
    private volatile String   smtpPassword;
    private volatile boolean  smtpAuth     = true;
    private volatile boolean  smtpStartTls = true;
    private volatile String   slaReportFrom;
    private volatile String   slaReportFromName;

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
        b2bTeamsDomain = (saved != null && saved.getB2bTeamsDomain() != null)
                     ? saved.getB2bTeamsDomain() : "";
        slaReportRecipients = (saved != null && saved.getSlaReportRecipients() != null)
                     ? saved.getSlaReportRecipients() : "";
        slaReportEnabled = (saved == null) || saved.isSlaReportEnabled();

        smtpHost          = saved != null ? saved.getSmtpHost()          : null;
        smtpPort          = saved != null ? saved.getSmtpPort()          : null;
        smtpUsername      = saved != null ? saved.getSmtpUsername()      : null;
        smtpPassword      = saved != null ? saved.getSmtpPassword()      : null;
        smtpAuth          = (saved == null) || saved.isSmtpAuth();
        smtpStartTls      = (saved == null) || saved.isSmtpStartTls();
        slaReportFrom     = saved != null ? saved.getSlaReportFrom()     : null;
        slaReportFromName = saved != null ? saved.getSlaReportFromName() : null;
    }

    public String getUrl()        { return props.getUrl(); } // always from application.properties
    public String getEmail()      { return email; }
    public String getApiToken()   { return apiToken; }
    public String getSlaFieldId() { return slaFieldId != null ? slaFieldId : ""; }
    public String getWebhookUrl() { return webhookUrl != null ? webhookUrl : ""; }
    public String getB2bWebhookUrl() { return b2bWebhookUrl != null ? b2bWebhookUrl : ""; }
    public String getB2bTeamsDomain() { return b2bTeamsDomain != null ? b2bTeamsDomain : ""; }

    /** Raw recipient string as typed in the Admin tab. */
    public String getSlaReportRecipients() { return slaReportRecipients != null ? slaReportRecipients : ""; }

    /**
     * Recipients split on comma / semicolon / whitespace, trimmed, de-duplicated,
     * and filtered to entries that look like an address.
     */
    public java.util.List<String> getSlaReportRecipientList() {
        String raw = getSlaReportRecipients();
        if (raw.isBlank()) return java.util.List.of();
        return java.util.Arrays.stream(raw.split("[,;\\s]+"))
            .map(String::trim)
            .filter(s -> s.contains("@"))
            .distinct()
            .toList();
    }

    public boolean isSlaReportEnabled() { return slaReportEnabled; }

    // --- SMTP (DB-configured; empty host means "fall back to spring.mail.* env vars") ---
    public String  getSmtpHost()          { return smtpHost     != null ? smtpHost     : ""; }
    public Integer getSmtpPort()          { return smtpPort; }
    public String  getSmtpUsername()      { return smtpUsername != null ? smtpUsername : ""; }
    public String  getSmtpPassword()      { return smtpPassword != null ? smtpPassword : ""; }
    public boolean isSmtpAuth()           { return smtpAuth; }
    public boolean isSmtpStartTls()       { return smtpStartTls; }
    public String  getSlaReportFrom()     { return slaReportFrom     != null ? slaReportFrom     : ""; }
    public String  getSlaReportFromName() { return slaReportFromName != null ? slaReportFromName : ""; }

    /** True when the UI has supplied a mail server, which then overrides the env vars. */
    public boolean hasDbSmtp() { return smtpHost != null && !smtpHost.isBlank(); }

    /**
     * Saves the mail-server settings from the Admin UI.
     * A blank {@code password} keeps the stored one (the UI never receives it back).
     */
    public void saveSmtpSettings(String host, Integer port, String username, String password,
                                 boolean auth, boolean startTls, String from, String fromName) {
        JiraConfig cfg = repo.findById(1L).orElse(new JiraConfig());

        String h = host == null ? "" : host.trim();
        cfg.setSmtpHost(h);
        cfg.setSmtpPort(port);
        cfg.setSmtpUsername(username == null ? "" : username.trim());
        if (password != null && !password.isBlank()) cfg.setSmtpPassword(password.trim());
        cfg.setSmtpAuth(auth);
        cfg.setSmtpStartTls(startTls);
        cfg.setSlaReportFrom(from         == null ? "" : from.trim());
        cfg.setSlaReportFromName(fromName == null ? "" : fromName.trim());
        repo.save(cfg);

        this.smtpHost          = cfg.getSmtpHost();
        this.smtpPort          = cfg.getSmtpPort();
        this.smtpUsername      = cfg.getSmtpUsername();
        this.smtpPassword      = cfg.getSmtpPassword();
        this.smtpAuth          = cfg.isSmtpAuth();
        this.smtpStartTls      = cfg.isSmtpStartTls();
        this.slaReportFrom     = cfg.getSlaReportFrom();
        this.slaReportFromName = cfg.getSlaReportFromName();
    }

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

    public void saveSlaReportSettings(String recipients, boolean enabled) {
        String r = recipients == null ? "" : recipients.trim();
        JiraConfig cfg = repo.findById(1L).orElse(new JiraConfig());
        cfg.setSlaReportRecipients(r);
        cfg.setSlaReportEnabled(enabled);
        repo.save(cfg);
        this.slaReportRecipients = r;
        this.slaReportEnabled    = enabled;
    }

    public void saveB2bTeamsDomain(String domain) {
        // Accept "prodapt.com" or "@prodapt.com" — store the bare domain.
        String d = domain == null ? "" : domain.trim().replaceFirst("^@", "");
        JiraConfig cfg = repo.findById(1L).orElse(new JiraConfig());
        cfg.setB2bTeamsDomain(d);
        repo.save(cfg);
        this.b2bTeamsDomain = d;
    }
}
