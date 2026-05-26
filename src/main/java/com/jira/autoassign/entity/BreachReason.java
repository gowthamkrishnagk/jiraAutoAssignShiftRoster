package com.jira.autoassign.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "breach_reasons")
public class BreachReason {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String label;

    public BreachReason() {}
    public BreachReason(String label) { this.label = label; }

    public Long   getId()            { return id; }
    public void   setId(Long id)     { this.id = id; }
    public String getLabel()         { return label; }
    public void   setLabel(String l) { this.label = l; }
}
