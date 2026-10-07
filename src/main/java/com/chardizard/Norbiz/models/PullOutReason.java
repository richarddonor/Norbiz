package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

// Why stock was pulled out of an outlet (e.g. damaged, slow-moving). Picked on every Outlet Pull Out.
@Getter
@Setter
@Entity
@Table(
    name = "pull_out_reasons",
    uniqueConstraints = @UniqueConstraint(name = "PULL_OUT_REASONS_COMPANY_NAME_UQ", columnNames = {"company_id", "name"})
)
public class PullOutReason extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "PULL_OUT_REASONS_COMPANY_ID_FK"))
    private Company company;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false)
    private boolean active = true;
}
