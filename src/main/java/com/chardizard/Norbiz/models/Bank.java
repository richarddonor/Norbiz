package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

// A bank (legacy tblBanks): the drawee bank of a customer's check, and the bank behind each of our BankAccounts.
@Getter
@Setter
@Entity
@Table(
    name = "banks",
    uniqueConstraints = @UniqueConstraint(name = "BANKS_COMPANY_CODE_UQ", columnNames = {"company_id", "code"})
)
public class Bank extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "BANKS_COMPANY_ID_FK"))
    private Company company;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false)
    private boolean active = true;
}
