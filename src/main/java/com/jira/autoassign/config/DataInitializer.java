package com.jira.autoassign.config;

import com.jira.autoassign.entity.BreachReason;
import com.jira.autoassign.entity.Team;
import com.jira.autoassign.repository.BreachReasonRepository;
import com.jira.autoassign.repository.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seeds the default teams on first startup if they don't already exist in the DB.
 * Re-running the app never overwrites teams that were modified at runtime.
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final TeamRepository        teamRepository;
    private final BreachReasonRepository breachReasonRepository;
    private final JiraProperties         props;

    public DataInitializer(TeamRepository teamRepository,
                           BreachReasonRepository breachReasonRepository,
                           JiraProperties props) {
        this.teamRepository        = teamRepository;
        this.breachReasonRepository = breachReasonRepository;
        this.props                  = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        String ofJql = props.getCustomJql() != null ? props.getCustomJql() : "";

        if (!teamRepository.existsById("orderfallout")) {
            teamRepository.save(new Team("orderfallout", "Order Fallout", ofJql));
            log.info("Seeded team: Order Fallout");
        }

        // Seed default breach reasons if none exist yet
        if (breachReasonRepository.count() == 0) {
            List.of(
                "Aria Escalation", "Matrix Escalation", "Nokia Escalation",
                "No workaround", "Network Clean up", "Outage", "High inflow",
                "L2 Support", "B2B Support", "Request from L3 to Hold"
            ).forEach(label -> breachReasonRepository.save(new BreachReason(label)));
            log.info("Seeded default breach reasons");
        }

        if (!teamRepository.existsById("sac")) {
            String sacJql = ofJql.replace(
                "\"Reporting Area[Dropdown]\" = \"Order Fallout\"",
                "\"Reporting Area[Dropdown]\" != \"Order Fallout\""
            );
            teamRepository.save(new Team("sac", "SAC Team", sacJql));
            log.info("Seeded team: SAC Team");
        }
    }
}
