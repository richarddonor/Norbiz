package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.audit.AuditAction;
import com.chardizard.Norbiz.dto.BrandRequest;
import com.chardizard.Norbiz.dto.ChangeHistoryEntryResponse;
import com.chardizard.Norbiz.dto.DeletedRecordResponse;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Runs against the configured Postgres (same as NorbizApplicationTests); every test rolls back.
@SpringBootTest
@Transactional
class DeletedMasterDataHistoryTest {

    @Autowired MasterDataHistoryService masterDataHistoryService;
    @Autowired BrandService brandService;
    @Autowired EntityManager entityManager;
    @Autowired CompanyRepository companyRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PermissionRepository permissionRepository;
    @Autowired UserRepository userRepository;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private Company company;
    private String member;     // VIEW_BRAND in company
    private String outsider;   // VIEW_BRAND in another company
    private String noView;     // in company, no VIEW_BRAND
    private Long brandId;

    @BeforeEach
    void setUp() {
        company = company("DH Co " + suffix);
        Company otherCompany = company("DH Other " + suffix);
        Role viewer = role("DH_VIEWER_" + suffix, permissionRepository.findByName("VIEW_BRAND").orElseThrow());
        Role nothing = role("DH_NOTHING_" + suffix);
        member = user("dh_member_" + suffix, company, viewer);
        outsider = user("dh_out_" + suffix, otherCompany, viewer);
        noView = user("dh_noview_" + suffix, company, nothing);

        BrandRequest request = new BrandRequest();
        request.setCompanyId(company.getId());
        request.setName("DH Brand " + suffix);
        brandId = brandService.create(request, member).getId();
        brandService.delete(brandId, member);
        entityManager.flush();
    }

    @Test
    void deletedRecordIsListedForItsCompany() {
        var page = masterDataHistoryService.findDeleted(MasterDataType.BRAND, company.getId(), Map.of(), null, null, member, PageRequest.of(0, 50));
        assertThat(page.getContent()).extracting(DeletedRecordResponse::getId).containsExactly(brandId);
        DeletedRecordResponse record = page.getContent().getFirst();
        assertThat(record.getName()).isEqualTo("DH Brand " + suffix);
        assertThat(record.getCompanyId()).isEqualTo(company.getId());
        assertThat(record.getDeletedAt()).isNotNull();
    }

    @Test
    void deletedListIsScopedToTheCallersCompanies() {
        var mine = masterDataHistoryService.findDeleted(MasterDataType.BRAND, null, Map.of("name", "dh brand " + suffix), null, null, member, PageRequest.of(0, 50));
        assertThat(mine.getContent()).extracting(DeletedRecordResponse::getId).containsExactly(brandId);

        var theirs = masterDataHistoryService.findDeleted(MasterDataType.BRAND, null, Map.of("name", suffix), null, null, outsider, PageRequest.of(0, 50));
        assertThat(theirs.getContent()).isEmpty();

        assertThatThrownBy(() -> masterDataHistoryService.findDeleted(MasterDataType.BRAND, company.getId(), Map.of(), null, null, outsider, PageRequest.of(0, 50)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void nameFilterExcludesNonMatches() {
        var page = masterDataHistoryService.findDeleted(MasterDataType.BRAND, company.getId(), Map.of("name", "no-such-" + suffix), null, null, member, PageRequest.of(0, 50));
        assertThat(page.getContent()).isEmpty();
    }

    @Test
    void historyOfDeletedRecordIsReadable() {
        // Create and delete share the test's one transaction, hence one change set — so one entry.
        var history = masterDataHistoryService.findHistory(MasterDataType.BRAND, brandId, member, PageRequest.of(0, 50));
        assertThat(history.getContent()).extracting(ChangeHistoryEntryResponse::getAction).containsExactly(AuditAction.CREATE);
    }

    @Test
    void historyOfDeletedRecordChecksItsCompanyAndPermission() {
        assertThatThrownBy(() -> masterDataHistoryService.findHistory(MasterDataType.BRAND, brandId, outsider, PageRequest.of(0, 50)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> masterDataHistoryService.findHistory(MasterDataType.BRAND, brandId, noView, PageRequest.of(0, 50)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void deletedSkusAreNotListedOnTheirOwn() {
        assertThatThrownBy(() -> masterDataHistoryService.findDeleted(MasterDataType.ITEM_SKU, null, Map.of(), null, null, member, PageRequest.of(0, 50)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Company company(String name) {
        Company c = new Company();
        c.setName(name);
        return companyRepository.save(c);
    }

    private Role role(String name, Permission... permissions) {
        Role r = new Role();
        r.setName(name);
        r.setPermissions(Set.of(permissions));
        return roleRepository.save(r);
    }

    private String user(String username, Company company, Role... roles) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@test.local");
        u.setPassword("x");
        u.setCompanies(Set.of(company));
        u.setRoles(Set.of(roles));
        userRepository.save(u);
        return username;
    }
}
