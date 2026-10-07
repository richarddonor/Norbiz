package com.chardizard.Norbiz.models;

import com.chardizard.Norbiz.audit.AuditParent;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

// One raw-material component of a bill of materials: quantity needed per unit of the output item.
@Getter
@Setter
@Entity
@Table(name = "bill_of_material_lines")
public class BillOfMaterialLine extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @AuditParent
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_of_material_id", nullable = false,
        foreignKey = @ForeignKey(name = "BILL_OF_MATERIAL_LINES_BILL_OF_MATERIAL_ID_FK"))
    private BillOfMaterial billOfMaterial;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false,
        foreignKey = @ForeignKey(name = "BILL_OF_MATERIAL_LINES_ITEM_ID_FK"))
    private Item item;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;
}
