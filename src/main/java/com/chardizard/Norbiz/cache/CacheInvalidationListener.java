package com.chardizard.Norbiz.cache;

import lombok.RequiredArgsConstructor;
import org.hibernate.event.spi.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Hibernate listener that turns every entity write — whichever service or side effect made it
 * (e.g. a Purchase Receive flipping {@code PurchaseOrder.loaded}) — into a generation bump.
 *
 * Writes are collected per transaction and bumped once in {@code afterCompletion(COMMITTED)}, so a
 * rolled-back transaction invalidates nothing and readers never see a bump before the data commits.
 * Collection events cover changes that touch only a join/element table (item tags, user companies,
 * role permissions), which don't fire an entity update.
 *
 * Bulk JPQL/native {@code UPDATE}/{@code DELETE} bypass Hibernate events — any such query must bump
 * {@link GenerationStore} itself.
 */
@RequiredArgsConstructor
public class CacheInvalidationListener implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener,
        PostCollectionRecreateEventListener, PostCollectionUpdateEventListener, PostCollectionRemoveEventListener {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationListener.class);
    private static final Object PENDING_KEY = CacheInvalidationListener.class.getName() + ".pending";

    private final GenerationStore generations;

    @Override
    public void onPostInsert(PostInsertEvent event) {
        record(event.getEntity());
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        record(event.getEntity());
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        record(event.getEntity());
    }

    @Override
    public void onPostRecreateCollection(PostCollectionRecreateEvent event) {
        record(event.getAffectedOwnerOrNull());
    }

    @Override
    public void onPostUpdateCollection(PostCollectionUpdateEvent event) {
        record(event.getAffectedOwnerOrNull());
    }

    @Override
    public void onPostRemoveCollection(PostCollectionRemoveEvent event) {
        record(event.getAffectedOwnerOrNull());
    }

    void record(Object entity) {
        if (entity == null) return;
        CompanyResolver.Target target;
        try {
            target = CompanyResolver.resolve(entity);
        } catch (RuntimeException ex) {
            // Never fail the business write over cache bookkeeping.
            log.warn("Cannot resolve cache generation for {}: {}", entity.getClass().getSimpleName(), ex.getMessage());
            return;
        }
        if (target == null) return;

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            generations.bump(Set.of(target));
            return;
        }
        pending().add(target);
    }

    @SuppressWarnings("unchecked")
    private Set<CompanyResolver.Target> pending() {
        Set<CompanyResolver.Target> pending = (Set<CompanyResolver.Target>) TransactionSynchronizationManager.getResource(PENDING_KEY);
        if (pending != null) return pending;

        Set<CompanyResolver.Target> created = new LinkedHashSet<>();
        TransactionSynchronizationManager.bindResource(PENDING_KEY, created);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void suspend() {
                TransactionSynchronizationManager.unbindResource(PENDING_KEY);
            }

            @Override
            public void resume() {
                TransactionSynchronizationManager.bindResource(PENDING_KEY, created);
            }

            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(PENDING_KEY);
                if (status == STATUS_COMMITTED) generations.bump(created);
            }
        });
        return created;
    }
}
