package com.jira.autoassign.service;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AssignmentPriorityTest {
    @Test
    void updatesCountsAndRotatesFairlyOnceWorkloadsAreEqual() {
        List<String> ids = List.of("a", "b");
        AssignmentPriority priority = new AssignmentPriority(ids, 0, Map.of("a", 1, "b", 4));
        List<String> recipients = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String id = ids.get(AssignmentPriority.nextIndex(ids, 0, priority));
            recipients.add(id);
            priority.assignedTo(id);
        }
        assertEquals(List.of("a", "a", "a", "b", "a", "b"), recipients);
    }

    @Test
    void skipsRemovedMembersAndCountsTheReceivingCoverAccountOnce() {
        List<String> ids = new ArrayList<>(List.of("cover", "cover", "b"));
        AssignmentPriority priority = new AssignmentPriority(ids, 0, Map.of("cover", 0, "b", 1));
        assertEquals(0, AssignmentPriority.nextIndex(ids, 0, priority));
        priority.assignedTo("cover");
        assertEquals(2, AssignmentPriority.nextIndex(ids, 0, priority));
        ids.remove("b");
        assertEquals(0, AssignmentPriority.nextIndex(ids, 0, priority));
    }

    @Test
    void tiesStartAtTheSavedRotationAndOtherTeamsKeepRoundRobin() {
        List<String> ids = List.of("a", "b", "c");
        AssignmentPriority priority = new AssignmentPriority(ids, 2, Map.of("a", 0, "b", 0, "c", 0));
        assertEquals(2, AssignmentPriority.nextIndex(ids, 0, priority));
        assertEquals(1, AssignmentPriority.nextIndex(ids, 4, null));
    }
}
