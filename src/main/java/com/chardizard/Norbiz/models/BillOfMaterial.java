package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

// Recipe for assembling one unit of an output item from raw-material components. An Assembly form
// prefills its material lines from a BOM (components x quantity assembled).
@Getter
@Setter
@Entity
@Table(
    name = "bills_of_materials",
    uniqueConstraints = @UniqueConstraint(name = "BILLS_OF_MATERIALS_COMPANY_CODE_UQ", columnNames = {"company_id", "code"})
)
public class BillOfMaterial extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "BILLS_OF_MATERIALS_COMPANY_ID_FK"))
    private Company company;

    @Column(nullable = false, length = 100)
    private String code;

    // The finished item one unit of this BOM produces.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false,
        foreignKey = @ForeignKey(name = "BILLS_OF_MATERIALS_ITEM_ID_FK"))
    private Item item;

    @Column(nullable = false)
    private boolean active = true;

    @OneToMany(mappedBy = "billOfMaterial", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<BillOfMaterialLine> components = new ArrayList<>();
}
