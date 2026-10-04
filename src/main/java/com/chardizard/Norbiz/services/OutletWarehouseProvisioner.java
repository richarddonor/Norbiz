package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.models.Customer;
import com.chardizard.Norbiz.models.CustomerType;
import com.chardizard.Norbiz.repositories.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives every OUTLET customer its own warehouse. New outlets get one from CustomerService; this fills
 * the gap for outlets created before the link existed. Idempotent — outlets already linked are skipped.
 * Runs on startup via DataInitializer.
 */
@Service
@RequiredArgsConstructor
public class OutletWarehouseProvisioner {

    private static final Logger log = LoggerFactory.getLogger(OutletWarehouseProvisioner.class);
    private static final String SYSTEM_USER = "system";

    private final CustomerRepository customerRepository;
    private final CustomerService customerService;

    @Transactional
    public void ensureForAllOutlets() {
        for (Customer outlet : customerRepository.findByTypeAndWarehouseIsNull(CustomerType.OUTLET)) {
            try {
                outlet.setWarehouse(customerService.createOutletWarehouse(outlet, SYSTEM_USER));
                customerRepository.save(outlet);
            } catch (IllegalArgumentException e) {
                // e.g. the outlet's code is already used by a warehouse — needs a manual rename, mustn't block startup
                log.warn("Could not create a warehouse for outlet '{}' (id={}): {}", outlet.getName(), outlet.getId(), e.getMessage());
            }
        }
    }
}
