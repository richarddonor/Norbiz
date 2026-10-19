package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.controllers.ItemImageController;
import com.chardizard.Norbiz.dto.ItemRecognitionMatchResponse;
import com.chardizard.Norbiz.exceptions.ItemRecognitionUnavailableException;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Runs against the configured Postgres, which needs pgvector (Dockerfile.db + migration 005); the
// embedder sidecar is mocked with hand-made vectors. Every test rolls back.
@SpringBootTest(properties = "app.item-image.upload-dir=${java.io.tmpdir}/norbiz-recognition-test")
@Transactional
class ItemRecognitionTest {

    private static final String MODEL = "test-model";

    @Autowired ItemRecognitionService recognitionService;
    @Autowired ItemImageController itemImageController;
    @Autowired CompanyRepository companyRepository;
    @Autowired UserRepository userRepository;
    @Autowired ItemCategoryRepository itemCategoryRepository;
    @Autowired ItemRepository itemRepository;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ImageEmbeddingClient embeddingClient;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final byte[] photo = {1, 2, 3};

    private Company company;
    private String clerk;
    private String outsider;
    private Item red;
    private Item blue;
    private Item dormant;

    @BeforeEach
    void setUp() {
        company = company("IR Co " + suffix);
        Company otherCompany = company("IR Other " + suffix);
        clerk = user("ir_clerk_" + suffix, company);
        outsider = user("ir_out_" + suffix, otherCompany);

        red = item(company, "RED", true, Set.of(ItemTag.INVENTORY));
        blue = item(company, "BLUE", true, Set.of());
        dormant = item(company, "OLD", false, Set.of(ItemTag.INVENTORY));
        Item foreign = item(otherCompany, "FOREIGN", true, Set.of(ItemTag.INVENTORY));

        index(red, unit(0));
        index(blue, unit(1));
        index(dormant, mix(0, 2));   // close to red, ~0.71
        index(foreign, unit(0));     // identical to red, other company
    }

    @Test
    void returnsMostSimilarActiveItemsOfTheCompanyOnly() {
        List<ItemRecognitionMatchResponse> matches = recognize(unit(0), null, true);

        // blue (similarity 0) is under min-similarity, dormant is inactive, foreign is another company's.
        assertThat(matches).extracting(m -> m.getItem().getId()).containsExactly(red.getId());
        assertThat(matches.getFirst().getSimilarity()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(matches.getFirst().getImagePath()).isEqualTo(red.getImagePath());
    }

    @Test
    void ranksBySimilarityAndCanIncludeInactiveItems() {
        List<ItemRecognitionMatchResponse> matches = recognize(unit(0), null, false);

        assertThat(matches).extracting(m -> m.getItem().getId()).containsExactly(red.getId(), dormant.getId());
    }

    @Test
    void tagFilterKeepsOnlyTaggedItems() {
        // Equally close to red and blue (~0.71); only red carries INVENTORY.
        List<ItemRecognitionMatchResponse> matches = recognize(mix(0, 1), ItemTag.INVENTORY, true);

        assertThat(matches).extracting(m -> m.getItem().getId()).containsExactly(red.getId());
    }

    @Test
    void reindexingAnItemReplacesItsEmbedding() {
        index(red, unit(1));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM item_image_embeddings WHERE item_id = ?", Long.class, red.getId()))
                .isEqualTo(1);
        assertThat(recognize(unit(1), null, true)).extracting(m -> m.getItem().getId())
                .containsExactlyInAnyOrder(red.getId(), blue.getId());
    }

    @Test
    void embeddingsOfAnotherModelAreNotCompared() {
        when(embeddingClient.embed(any())).thenReturn(new ImageEmbeddingClient.Embedding("other-model", unit(0)));

        assertThat(recognitionService.recognize(clerk, company.getId(), photo, null, true, 5, false)).isEmpty();
    }

    @Test
    void cannotRecognizeAgainstAnotherCompany() {
        when(embeddingClient.embed(any())).thenReturn(new ImageEmbeddingClient.Embedding(MODEL, unit(0)));
        clearInvocations(embeddingClient);   // setUp's indexing already called it

        assertThatThrownBy(() -> recognitionService.recognize(outsider, company.getId(), photo, null, true, 5, false))
                .isInstanceOf(SecurityException.class);
        verify(embeddingClient, never()).embed(photo);
    }

    @Test
    void uploadIndexFailureDoesNotFailTheUpload() {
        when(embeddingClient.embed(any())).thenThrow(new ItemRecognitionUnavailableException("down"));

        assertThat(recognitionService.indexQuietly(blue, photo)).isFalse();
    }

    @Test
    @WithMockUser(authorities = "UPDATE_ITEM")
    void imageUploadIsCompanyScoped() {
        UserDetails outsiderDetails = org.springframework.security.core.userdetails.User.withUsername(outsider)
                .password("x").authorities("UPDATE_ITEM").build();
        MockMultipartFile file = new MockMultipartFile("file", "red.png", "image/png", photo);

        assertThatThrownBy(() -> itemImageController.uploadItemImage(outsiderDetails, red.getId(), file))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @WithMockUser(authorities = "UPDATE_ITEM")
    void reindexNeedsItsOwnPermissionNotUpdateItem() {
        UserDetails editor = org.springframework.security.core.userdetails.User.withUsername(clerk)
                .password("x").authorities("UPDATE_ITEM").build();

        assertThatThrownBy(() -> itemImageController.reindexItemImages(editor, company.getId(), null, false))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    // ---- fixtures ----

    private List<ItemRecognitionMatchResponse> recognize(float[] query, ItemTag tag, boolean activeOnly) {
        when(embeddingClient.embed(any())).thenReturn(new ImageEmbeddingClient.Embedding(MODEL, query));
        return recognitionService.recognize(clerk, company.getId(), photo, tag, activeOnly, 5, false);
    }

    private void index(Item item, float[] vector) {
        when(embeddingClient.embed(any())).thenReturn(new ImageEmbeddingClient.Embedding(MODEL, vector));
        assertThat(recognitionService.indexQuietly(item, photo)).isTrue();
    }

    private static float[] unit(int axis) {
        float[] v = new float[ImageEmbeddingClient.DIMENSION];
        v[axis] = 1f;
        return v;
    }

    private static float[] mix(int a, int b) {
        float[] v = new float[ImageEmbeddingClient.DIMENSION];
        v[a] = v[b] = (float) Math.sqrt(0.5);
        return v;
    }

    private Item item(Company owner, String code, boolean active, Set<ItemTag> tags) {
        ItemCategory category = new ItemCategory();
        category.setCompany(owner);
        category.setName("IR Cat " + code + " " + suffix);
        category = itemCategoryRepository.save(category);

        Item i = new Item();
        i.setCompany(owner);
        i.setItemCategory(category);
        i.setItemCode("IR" + code + suffix);
        i.setName("IR Item " + code);
        i.setActive(active);
        i.setImagePath(owner.getId() + "/items/images/IR" + code + suffix + ".png");
        i.getTags().addAll(tags);
        return itemRepository.saveAndFlush(i);
    }

    private Company company(String name) {
        Company c = new Company();
        c.setName(name);
        return companyRepository.save(c);
    }

    private String user(String username, Company company) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@test.local");
        u.setPassword("x");
        u.setCompanies(Set.of(company));
        userRepository.save(u);
        return username;
    }
}
