package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;

// Not Auditable: immutable once posted (see Assembly).
// Always a positive quantity; kind decides whether it adds (OUTPUT) or deducts (MATERIAL) stock.
@Getter
@Setter
@Entity
@Table(name = "assembly_lines")
public class AssemblyLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assembly_id", nullable = false,
        foreignKey = @ForeignKey(name = "ASSEMBLY_LINES_ASSEMBLY_ID_FK"))
    private Assembly assembly;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false,
        foreignKey = @ForeignKey(name = "ASSEMBLY_LINES_ITEM_ID_FK"))
    private Item item;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AssemblyLineKind kind;

    // OUTPUT lines only, optional: the bill of materials the output was built from.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bill_of_material_id",
        foreignKey = @ForeignKey(name = "ASSEMBLY_LINES_BILL_OF_MATERIAL_ID_FK"))
    private BillOfMaterial billOfMaterial;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    // Inert: nothing loads from an Assembly.
    @Column(name = "quantity_loaded", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantityLoaded = BigDecimal.ZERO;
}
