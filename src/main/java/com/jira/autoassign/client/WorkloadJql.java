package com.jira.autoassign.client;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Derives the SAC count scope without changing its assignment query. */
public final class WorkloadJql {
    private static final Pattern PERSON_OR_STATUS = Pattern.compile(
        "(?is)^\\s*(?:status|statusCategory|assignee|\"status\"|\"statusCategory\"|\"assignee\")\\s*(?:=|!=|\\bIN\\b|\\bNOT\\b|\\bIS\\b|[<>]).*");

    private WorkloadJql() { }

    public static String countScope(String jql) {
        String expression = split(jql == null ? "" : jql.trim(), "ORDER BY").get(0).trim();
        return prune(expression);
    }

    public static String forAssignee(String jql, String accountId) {
        String scope = countScope(jql);
        String assignee = "assignee = \"" + accountId.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        return (scope.isBlank() ? "" : "(" + scope + ") AND ") + assignee + " ORDER BY created ASC";
    }

    public static String forAssignees(String jql, java.util.Collection<String> accountIds) {
        String scope = countScope(jql);
        String people = accountIds.stream().distinct()
            .map(id -> "\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
            .collect(java.util.stream.Collectors.joining(", "));
        return (scope.isBlank() ? "" : "(" + scope + ") AND ")
            + "assignee IN (" + people + ") ORDER BY created ASC";
    }

    private static String prune(String expression) {
        expression = expression.trim();
        if (wrapped(expression)) {
            String inner = prune(expression.substring(1, expression.length() - 1));
            return inner.isBlank() ? "" : "(" + inner + ")";
        }
        for (String operator : List.of("OR", "AND")) {
            List<String> parts = split(expression, operator);
            if (parts.size() > 1) {
                return parts.stream().map(WorkloadJql::prune).filter(s -> !s.isBlank())
                    .collect(java.util.stream.Collectors.joining(" " + operator + " "));
            }
        }
        return PERSON_OR_STATUS.matcher(expression).matches() ? "" : expression;
    }

    private static boolean wrapped(String value) {
        if (!value.startsWith("(") || !value.endsWith(")")) return false;
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') { i++; continue; }
            if (quote != 0) { if (c == quote) quote = 0; continue; }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (c == '(') depth++;
            if (c == ')' && --depth == 0 && i < value.length() - 1) return false;
        }
        return depth == 0;
    }

    private static List<String> split(String value, String operator) {
        Pattern keyword = Pattern.compile("(?i)\\b" + operator.replace(" ", "\\s+") + "\\b");
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') { i++; continue; }
            if (quote != 0) { if (c == quote) quote = 0; continue; }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (c == '(') { depth++; continue; }
            if (c == ')') { depth--; continue; }
            if (depth != 0 || (i > 0 && Character.isJavaIdentifierPart(value.charAt(i - 1)))) continue;
            var match = keyword.matcher(value).region(i, value.length());
            if (match.lookingAt()) {
                parts.add(value.substring(start, i));
                start = match.end();
                i = start - 1;
            }
        }
        parts.add(value.substring(start));
        return parts;
    }
}
