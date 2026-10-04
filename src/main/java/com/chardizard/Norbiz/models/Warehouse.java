package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

@Getter
@Setter
@Entity
@Table(
    name = "warehouses",
    uniqueConstraints = @UniqueConstraint(name = "WAREHOUSES_COMPANY_CODE_UQ", columnNames = {"company_id", "code"})
)
public class Warehouse extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "WAREHOUSES_COMPANY_ID_FK"))
    private Company company;

    @Column(length = 100)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    // The company's main warehouse: Delivery Receipts deduct stock from it. At most one per company
    // (WarehouseService clears the previous one; partial unique index WAREHOUSES_COMPANY_MAIN_UQ backstops it).
    // ColumnDefault: lets ddl-auto=update add the NOT NULL column to existing rows.
    @ColumnDefault("false")
    @Column(nullable = false)
    private boolean main = false;

    // True for the warehouse auto-created for an OUTLET customer (see CustomerService) — it's managed
    // through that customer, so WarehouseService refuses direct edits/deletes and it can't be main.
    @ColumnDefault("false")
    @Column(nullable = false)
    private boolean outlet = false;
}