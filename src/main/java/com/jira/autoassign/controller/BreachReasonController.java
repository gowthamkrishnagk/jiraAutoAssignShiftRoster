package com.jira.autoassign.controller;

import com.jira.autoassign.entity.BreachReason;
import com.jira.autoassign.repository.BreachReasonRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * CRUD for breach reason options shown in the SLA Tracker dropdown.
 *
 * GET    /api/breach-reasons          — list all (ordered by id)
 * POST   /api/breach-reasons          — add a new reason  { label }
 * PUT    /api/breach-reasons/{id}     — rename a reason   { label }
 * DELETE /api/breach-reasons/{id}     — remove a reason
 */
@RestController
@RequestMapping("/api/breach-reasons")
public class BreachReasonController {

    private final BreachReasonRepository repo;

    public BreachReasonController(BreachReasonRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<BreachReason> list() {
        return repo.findAll();
    }

    @PostMapping
    public ResponseEntity<?> add(@RequestBody Map<String, String> body) {
        String label = body.getOrDefault("label", "").trim();
        if (label.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "label is required"));
        return ResponseEntity.ok(repo.save(new BreachReason(label)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> rename(@PathVariable Long id,
                                    @RequestBody Map<String, String> body) {
        String label = body.getOrDefault("label", "").trim();
        if (label.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "label is required"));
        return repo.findById(id).map(r -> {
            r.setLabel(label);
            return ResponseEntity.ok(repo.save(r));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!repo.existsById(id))
            return ResponseEntity.notFound().build();
        repo.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "Deleted"));
    }
}
