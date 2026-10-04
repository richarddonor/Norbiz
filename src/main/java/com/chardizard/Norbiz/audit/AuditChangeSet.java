package com.chardizard.Norbiz.audit;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * One id per database transaction, stamped on every audit log written in it, so a single save
 * (an item plus the SKU/price rows it rewrote) can be read back as one change.
 */
final class AuditChangeSet {

    private static final Object KEY = new Object();

    private AuditChangeSet() {}

    static String current() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return UUID.randomUUID().toString();
        }
        String id = (String) TransactionSynchronizationManager.getResource(KEY);
        if (id == null) {
            id = UUID.randomUUID().toString();
            TransactionSynchronizationManager.bindResource(KEY, id);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(KEY);
                }
            });
        }
        return id;
    }
}
