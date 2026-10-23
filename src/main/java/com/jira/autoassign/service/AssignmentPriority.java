package com.jira.autoassign.service;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Comparator;
import java.util.PriorityQueue;

/** Chooses the smallest workload, breaking ties in the current round-robin order. */
final class AssignmentPriority {
    private record Member(String accountId, int count, long tieOrder) { }
    private final Map<String, Integer> counts = new HashMap<>();
    private final Map<String, Member> members = new HashMap<>();
    private final PriorityQueue<Member> queue = new PriorityQueue<>(
        Comparator.comparingInt(Member::count).thenComparingLong(Member::tieOrder));
    private long nextTieOrder;

    AssignmentPriority(List<String> accountIds, int rotation, Map<String, Integer> workloads) {
        counts.putAll(workloads);
        int start = Math.floorMod(rotation, accountIds.size());
        for (int offset = 0; offset < accountIds.size(); offset++) {
            String id = accountIds.get((start + offset) % accountIds.size());
            if (members.containsKey(id)) continue;
            Member member = new Member(id, counts.getOrDefault(id, 0), nextTieOrder++);
            members.put(id, member);
            queue.add(member);
        }
    }

    static int nextIndex(List<String> accountIds, int rotation, AssignmentPriority priority) {
        if (priority == null) return Math.floorMod(rotation, accountIds.size());
        while (!priority.queue.isEmpty() && !accountIds.contains(priority.queue.peek().accountId())) {
            priority.queue.poll();
        }
        return priority.queue.isEmpty() ? Math.floorMod(rotation, accountIds.size())
            : accountIds.indexOf(priority.queue.peek().accountId());
    }

    void assignedTo(String accountId) {
        Member previous = members.get(accountId);
        if (previous == null) return;
        if (queue.peek() == previous) queue.poll();
        else queue.remove(previous);
        int count = counts.merge(accountId, 1, Integer::sum);
        Member updated = new Member(accountId, count, nextTieOrder++);
        members.put(accountId, updated);
        queue.add(updated);
    }
}
