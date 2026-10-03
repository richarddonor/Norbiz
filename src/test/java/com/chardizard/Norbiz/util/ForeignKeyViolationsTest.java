package com.chardizard.Norbiz.util;

import com.chardizard.Norbiz.exceptions.EntityInUseException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class ForeignKeyViolationsTest {

    @Test
    void foreignKeyViolationBecomesEntityInUse() {
        SQLException sql = new SQLException(
                "ERROR: update or delete on table \"brand\" violates foreign key constraint \"fk_item_brand\" on table \"purchase_order_lines\"",
                "23503");
        DataIntegrityViolationException ex = new DataIntegrityViolationException("could not execute statement", sql);

        EntityInUseException result = ForeignKeyViolations.from(ex, "Brand", 7L).orElseThrow();

        assertThat(result.getEntity()).isEqualTo("Brand");
        assertThat(result.getEntityId()).isEqualTo(7L);
        assertThat(result.getReferencedBy()).isEqualTo("Purchase Order Lines");
        assertThat(result.getMessage()).isEqualTo("Brand cannot be deleted because it is still used by Purchase Order Lines");
    }

    @Test
    void unparseableMessageStillTranslates() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException("x", new SQLException("localized text", "23503"));

        EntityInUseException result = ForeignKeyViolations.from(ex, "Brand", 7L).orElseThrow();

        assertThat(result.getReferencedBy()).isNull();
        assertThat(result.getMessage()).isEqualTo("Brand cannot be deleted because it is still used by other records");
    }

    @Test
    void otherIntegrityViolationsAreNotTranslated() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException("dup", new SQLException("duplicate key", "23505"));

        assertThat(ForeignKeyViolations.from(ex, "Brand", 7L)).isEmpty();
    }
}
