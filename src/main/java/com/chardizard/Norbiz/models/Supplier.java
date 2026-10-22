package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.ColumnDefault;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
    name = "suppliers",
    uniqueConstraints = @UniqueConstraint(name = "SUPPLIERS_COMPANY_CODE_UQ", columnNames = {"company_id", "code"})
)
public class Supplier extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "SUPPLIERS_COMPANY_ID_FK"))
    private Company company;

    @Column(length = 100)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 255)
    private String email;

    @Column(length = 50)
    private String phone;

    @Column(nullable = false)
    private boolean active = true;

    // Payment terms in days (legacy tblTerms): invoices and charge memos default their due date to
    // document date + termDays. ColumnDefault: lets ddl-auto=update add the NOT NULL column to existing rows.
    @ColumnDefault("0")
    @Column(name = "term_days", nullable = false)
    private int termDays = 0;
}