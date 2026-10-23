package com.jira.autoassign.client;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorkloadJqlTest {
    @Test
    void removesStatusAndUnassignedClausesButPreservesTeamScope() {
        String query = "project = SAC AND status in (\"In Progress\", \"Waiting for support\") "
            + "AND \"Reporting Area[Dropdown]\" != \"Order Fallout\" AND Assignee in (EMPTY) "
            + "AND \"Escalation Path[Dropdown]\" is EMPTY ORDER BY created ASC";
        String count = WorkloadJql.forAssignees(query, List.of("person-a", "person-b"));
        assertFalse(count.toLowerCase().contains("status"));
        assertTrue(count.contains("\"Reporting Area[Dropdown]\" != \"Order Fallout\""));
        assertTrue(count.contains("\"Escalation Path[Dropdown]\" is EMPTY"));
        assertTrue(count.contains("assignee IN (\"person-a\", \"person-b\")"));
        assertEquals(1, count.split("assignee", -1).length - 1);
    }

    @Test
    void handlesNestedFiltersAndQuotedTextWithoutEditingTheAssignmentQuery() {
        String query = "(status = Open OR status != Closed) AND (project = SAC OR project = HELP) "
            + "AND summary ~ \"AND status = Open ORDER BY created\" AND assignee IS EMPTY ORDER   BY key DESC";
        String count = WorkloadJql.countScope(query);
        assertEquals("(project = SAC OR project = HELP) AND summary ~ \"AND status = Open ORDER BY created\"", count);
        assertTrue(query.startsWith("(status = Open"));
    }

    @Test
    void removesExistingPeopleAndStatusCategoryAndKeepsOtherClauses() {
        String query = "assignee in (oldPerson) AND statusCategory != Done AND project = SAC";
        assertEquals("project = SAC", WorkloadJql.countScope(query));
        assertEquals("(project = SAC) AND assignee = \"one\" ORDER BY created ASC", WorkloadJql.forAssignee(query, "one"));
    }
}
