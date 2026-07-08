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
    name = "employees",
    uniqueConstraints = @UniqueConstraint(name = "EMPLOYEES_COMPANY_CODE_UQ", columnNames = {"company_id", "employee_code"})
)
public class Employee extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "EMPLOYEES_COMPANY_ID_FK"))
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id",
        foreignKey = @ForeignKey(name = "EMPLOYEES_USER_ID_FK"))
    private User user;

    @Column(name = "employee_code", nullable = false, length = 100)
    private String employeeCode;

    @Column(name = "first_name", nullable = false, length = 255)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 255)
    private String lastName;

    @Column(length = 255)
    private String email;

    @Column(length = 50)
    private String phone;

    @Column(nullable = false)
    private boolean active = true;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "employee_tags",
        joinColumns = @JoinColumn(name = "employee_id",
            foreignKey = @ForeignKey(name = "EMPLOYEE_TAGS_EMPLOYEE_ID_FK"))
    )
    @Column(name = "tag", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private Set<EmployeeTag> tags = new HashSet<>();
}