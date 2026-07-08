package com.chardizard.Norbiz.models;

import com.chardizard.Norbiz.audit.AuditExclude;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

// Password must be stored as a BCrypt hash — never store plain text.

@Getter
@Setter
@Entity
@Table(
    name = "users",
    uniqueConstraints = {
        @UniqueConstraint(name = "USERS_USERNAME_UQ", columnNames = "username"),
        @UniqueConstraint(name = "USERS_EMAIL_UQ",    columnNames = "email")
    }
)
public class User extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String username;

    @Column(name = "display_name")
    private String displayName;

    @Column(nullable = false)
    private String email;

    @AuditExclude
    @Column(nullable = false)
    private String password;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "user_companies",
        joinColumns = @JoinColumn(name = "user_id",
            foreignKey = @ForeignKey(name = "USER_COMPANIES_USER_ID_FK")),
        inverseJoinColumns = @JoinColumn(name = "company_id",
            foreignKey = @ForeignKey(name = "USER_COMPANIES_COMPANY_ID_FK"))
    )
    private Set<Company> companies = new HashSet<>();

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "user_roles",
        joinColumns = @JoinColumn(name = "user_id",
            foreignKey = @ForeignKey(name = "USER_ROLES_USER_ID_FK")),
        inverseJoinColumns = @JoinColumn(name = "role_id",
            foreignKey = @ForeignKey(name = "USER_ROLES_ROLE_ID_FK"))
    )
    private Set<Role> roles = new HashSet<>();
}