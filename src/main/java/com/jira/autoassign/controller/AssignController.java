package com.jira.autoassign.controller;

import com.jira.autoassign.service.ShiftAssignService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/assign")
public class AssignController {

    private final ShiftAssignService assignService;

    public AssignController(ShiftAssignService assignService) {
        this.assignService = assignService;
    }

    /** Manually trigger an assignment run. Requires JWT. */
    @PostMapping("/run")
    public ResponseEntity<?> triggerRun() {
        assignService.runShiftAssignment();
        return ResponseEntity.ok(Map.of("message", "Assignment run triggered"));
    }
}
