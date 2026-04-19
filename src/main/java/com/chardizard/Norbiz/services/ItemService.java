package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.ItemRequest;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.ItemRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ItemService {

    private final ItemRepository itemRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final EntityManager entityManager;

    public List<Item> findAllForUser(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        if (isSuperAdmin) {
            return itemRepository.findAll();
        }

        List<Long> companyIds = user.getCompanies().stream()
                .map(Company::getId)
                .collect(Collectors.toList());

        return companyIds.isEmpty() ? List.of() : itemRepository.findByCompanyIdIn(companyIds);
    }

    @Transactional
    public Item create(ItemRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));

        assertCompanyAccess(username, company.getId());

        if (itemRepository.existsByCompanyIdAndItemCode(company.getId(), request.getItemCode())) {
            throw new IllegalArgumentException("Item code already exists for this company");
        }

        Item item = new Item();
        item.setCompany(company);
        item.setItemCode(request.getItemCode());
        item.setName(request.getName());
        item.setImagePath(request.getImagePath());
        applySkus(item, request);
        applyPrices(item, request);

        return itemRepository.save(item);
    }

    @Transactional
    public Item update(Long id, ItemRequest request, String username) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Item not found: " + id));

        assertCompanyAccess(username, item.getCompany().getId());

        item.setName(request.getName());
        // imagePath is managed exclusively by ItemImageController — do not overwrite here

        item.getSkus().clear();
        item.getPrices().clear();
        // Flush DELETEs to the DB now so the unique constraints don't fire
        // when the new rows are inserted below in the same transaction.
        entityManager.flush();
        applySkus(item, request);
        applyPrices(item, request);

        return itemRepository.save(item);
    }

    @Transactional
    public void delete(Long id, String username) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Item not found: " + id));

        assertCompanyAccess(username, item.getCompany().getId());
        itemRepository.delete(item);
    }

    private void applySkus(Item item, ItemRequest request) {
        if (request.getSkus() == null) return;
        for (String skuCode : request.getSkus()) {
            ItemSku sku = new ItemSku();
            sku.setItem(item);
            sku.setSkuCode(skuCode);
            item.getSkus().add(sku);
        }
    }

    private void applyPrices(Item item, ItemRequest request) {
        if (request.getPrices() == null) return;
        for (var priceReq : request.getPrices()) {
            ItemPrice price = new ItemPrice();
            price.setItem(item);
            price.setPriceType(priceReq.getPriceType());
            price.setAmount(priceReq.getAmount());
            item.getPrices().add(price);
        }
    }

    private void assertCompanyAccess(String username, Long companyId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        if (isSuperAdmin) return;

        boolean hasAccess = user.getCompanies().stream()
                .anyMatch(c -> c.getId().equals(companyId));

        if (!hasAccess) {
            throw new SecurityException("Access denied to company: " + companyId);
        }
    }
}
