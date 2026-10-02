package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

// Company-configured action that users can take on a transaction of a given type
// (e.g. Purchase Receive: BARCODE_DISTRIBUTION, BARCODE_PRINTING, ENCODED_BY).
// See docs/TRANSACTION_ACTIONS.md.
@Getter
@Setter
@Entity
@Table(
    name = "transaction_action_definitions",
    uniqueConstraints = @UniqueConstraint(name = "TXN_ACTION_DEF_COMPANY_TYPE_CODE_UQ",
        columnNames = {"company_id", "transaction_type", "code"})
)
public class TransactionActionDefinition extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "TXN_ACTION_DEFS_COMPANY_ID_FK"))
    private Company company;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 50)
    private TransactionType transactionType;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active = true;

    // All of these must already have been taken (by any user) on the transaction
    // before this action can be taken. Always same company + transactionType; acyclic.
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "transaction_action_definition_prerequisites",
        joinColumns = @JoinColumn(name = "action_definition_id",
            foreignKey = @ForeignKey(name = "TXN_ACTION_DEF_PREREQS_DEF_ID_FK")),
        inverseJoinColumns = @JoinColumn(name = "prerequisite_id",
            foreignKey = @ForeignKey(name = "TXN_ACTION_DEF_PREREQS_PREREQ_ID_FK"))
    )
    private Set<TransactionActionDefinition> prerequisites = new HashSet<>();

    // Users holding any of these roles may take the action (SUPER_ADMIN always may).
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "transaction_action_definition_roles",
        joinColumns = @JoinColumn(name = "action_definition_id",
            foreignKey = @ForeignKey(name = "TXN_ACTION_DEF_ROLES_DEF_ID_FK")),
        inverseJoinColumns = @JoinColumn(name = "role_id",
            foreignKey = @ForeignKey(name = "TXN_ACTION_DEF_ROLES_ROLE_ID_FK"))
    )
    private Set<Role> allowedRoles = new HashSet<>();
}
