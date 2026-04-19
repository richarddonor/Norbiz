package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.Item;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ItemRepository extends JpaRepository<Item, Long> {
    List<Item> findByCompanyIdIn(List<Long> companyIds);
    Optional<Item> findByCompanyIdAndItemCode(Long companyId, String itemCode);
    boolean existsByCompanyIdAndItemCode(Long companyId, String itemCode);
}
