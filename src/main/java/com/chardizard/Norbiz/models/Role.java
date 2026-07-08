package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

@Getter
@Setter
@Entity
@Table(
    name = "roles",
    uniqueConstraints = @UniqueConstraint(name = "ROLES_NAME_UQ", columnNames = "name")
)
public class Role extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "display_name")
    private String displayName;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "role_permissions",
        joinColumns = @JoinColumn(name = "role_id",
            foreignKey = @ForeignKey(name = "ROLE_PERMISSIONS_ROLE_ID_FK")),
        inverseJoinColumns = @JoinColumn(name = "permission_id",
            foreignKey = @ForeignKey(name = "ROLE_PERMISSIONS_PERMISSION_ID_FK"))
    )
    private Set<Permission> permissions = new HashSet<>();
}