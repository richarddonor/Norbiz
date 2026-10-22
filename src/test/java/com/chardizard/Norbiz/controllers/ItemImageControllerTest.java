package com.chardizard.Norbiz.controllers;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ItemImageControllerTest {

    @Test
    void versionedUploadsAreCachedForAYearAndImmutable() {
        assertThat(ItemImageController.cacheControl("AFDFK0351R-v1761170000000.jpg").getHeaderValue())
                .isEqualTo("max-age=31536000, public, immutable");
    }

    // Unversioned names can be overwritten in place (legacy migration, uploads before versioning).
    @Test
    void unversionedImagesAreCachedForADay() {
        assertThat(ItemImageController.cacheControl("AFDFK0351R.jpg").getHeaderValue()).isEqualTo("max-age=86400, public");
        // An item code that merely ends in -v<digits> isn't a version stamp.
        assertThat(ItemImageController.cacheControl("SHIRT-v2.png").getHeaderValue()).isEqualTo("max-age=86400, public");
        // Legacy collision names are <code>-<item id>.
        assertThat(ItemImageController.cacheControl("AFDFK0351R-4512.jpg").getHeaderValue()).isEqualTo("max-age=86400, public");
    }
}
