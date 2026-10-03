package com.chardizard.Norbiz.cache;

import com.chardizard.Norbiz.models.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CacheInvalidationListenerTest {

    private final GenerationStore generations = mock(GenerationStore.class);
    private final CacheInvalidationListener listener = new CacheInvalidationListener(generations);

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void commitBumpsEachTargetOnce() {
        TransactionSynchronizationManager.initSynchronization();
        Company company = company(7L);
        listener.record(brand(company));
        listener.record(brand(company));
        listener.record(line(company));
        verifyNoInteractions(generations);   // nothing until the transaction commits

        complete(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(bumped()).containsExactlyInAnyOrder(
                new CompanyResolver.Target("Brand", 7L),
                new CompanyResolver.Target("PurchaseOrderLine", 7L));
    }

    @Test
    void rollbackBumpsNothing() {
        TransactionSynchronizationManager.initSynchronization();
        listener.record(brand(company(7L)));

        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        verifyNoInteractions(generations);
    }

    @Test
    void writeOutsideATransactionBumpsImmediately() {
        listener.record(brand(company(7L)));
        assertThat(bumped()).containsExactly(new CompanyResolver.Target("Brand", 7L));
    }

    @Test
    void globalAndIgnoredEntities() {
        assertThat(CompanyResolver.resolve(new User())).isEqualTo(new CompanyResolver.Target("User", null));
        assertThat(CompanyResolver.resolve(new Role())).isEqualTo(new CompanyResolver.Target("Role", null));
        assertThat(CompanyResolver.resolve(new AuditLog())).isNull();
        assertThat(CompanyResolver.resolve(company(3L))).isEqualTo(new CompanyResolver.Target("Company", 3L));
    }

    @Test
    void globalScopeAndGlobalOnlyEntitiesReadTheAllCounter() {
        assertThat(GenerationStore.keysFor(CacheRegion.LIST_EMPLOYEE, CacheScope.companies(List.of(1L, 2L))))
                .containsExactly(
                        "norbiz:gen:Company:1", "norbiz:gen:Company:2",
                        "norbiz:gen:Employee:1", "norbiz:gen:Employee:2",
                        "norbiz:gen:User:all");
        assertThat(GenerationStore.keysFor(CacheRegion.LIST_BRAND, CacheScope.global()))
                .containsExactly("norbiz:gen:Brand:all", "norbiz:gen:Company:all");
    }

    @SuppressWarnings("unchecked")
    private Collection<CompanyResolver.Target> bumped() {
        ArgumentCaptor<Collection<CompanyResolver.Target>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(generations).bump(captor.capture());
        return captor.getValue();
    }

    private static void complete(int status) {
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        syncs.forEach(s -> s.afterCompletion(status));
    }

    private static Company company(Long id) {
        Company c = new Company();
        c.setId(id);
        return c;
    }

    private static Brand brand(Company company) {
        Brand b = new Brand();
        b.setCompany(company);
        return b;
    }

    private static PurchaseOrderLine line(Company company) {
        PurchaseOrder po = new PurchaseOrder();
        po.setCompany(company);
        PurchaseOrderLine l = new PurchaseOrderLine();
        l.setPurchaseOrder(po);
        return l;
    }
}
