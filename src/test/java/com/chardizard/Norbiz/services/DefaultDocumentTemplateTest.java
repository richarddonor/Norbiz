package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.models.DocumentTemplate;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.DocumentTemplateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Runs against the configured Postgres (same as NorbizApplicationTests); every test rolls back.
@SpringBootTest
@Transactional
class DefaultDocumentTemplateTest {

    @Autowired DocumentSchemaRegistry schemaRegistry;
    @Autowired DefaultDocumentTemplateFactory templateFactory;
    @Autowired DefaultDocumentTemplateProvisioner provisioner;
    @Autowired CompanyRepository companyRepository;
    @Autowired DocumentTemplateRepository documentTemplateRepository;
    @Autowired ObjectMapper objectMapper;

    @Test
    void everyRegisteredDocumentTypeBuildsAValidLayout() {
        // buildLayoutJson throws if a hint references a field/group missing from the schema
        for (String documentType : schemaRegistry.documentTypes()) {
            JsonNode layout = objectMapper.readTree(templateFactory.buildLayoutJson(documentType));
            assertThat(layout.get("pageSize").asString()).isEqualTo("A4");
            assertThat(layout.get("elements").size()).isGreaterThan(0);
        }
    }

    @Test
    void singleTableLayoutMatchesCanonicalCoordinates() {
        JsonNode elements = objectMapper.readTree(templateFactory.buildLayoutJson("INVENTORY_ADJUSTMENT")).get("elements");

        JsonNode table = find(elements, "lines-table");
        assertThat(table.get("y").asInt()).isEqualTo(252);
        assertThat(table.get("height").asInt()).isEqualTo(780);
        assertThat(table.get("binding").asString()).isEqualTo("lines");
        assertThat(find(elements, "counterparty-value").get("binding").asString()).isEqualTo("warehouseName");
        assertThat(find(elements, "remarks-value").get("binding").asString()).isEqualTo("reason");
        assertThat(find(elements, "date-value").get("binding").asString()).isEqualTo("adjustmentDate");
        assertThat(find(elements, "doc-title").get("text").asString()).isEqualTo("INVENTORY ADJUSTMENT");
    }

    @Test
    void purchaseInvoiceLayoutIncludesFeesTable() {
        JsonNode elements = objectMapper.readTree(templateFactory.buildLayoutJson("PURCHASE_INVOICE")).get("elements");

        JsonNode lines = find(elements, "lines-table");
        JsonNode fees = find(elements, "fees-table");
        assertThat(fees.get("binding").asString()).isEqualTo("fees");
        assertThat(fees.get("y").asInt()).isGreaterThan(lines.get("y").asInt() + lines.get("height").asInt());
        assertThat(fees.get("y").asInt() + fees.get("height").asInt()).isLessThanOrEqualTo(1050);
    }

    @Test
    void provisionerFillsOnlyMissingDefaults() {
        Company company = new Company();
        company.setName("Template Co " + UUID.randomUUID().toString().substring(0, 8));
        company = companyRepository.save(company);

        DocumentTemplate custom = new DocumentTemplate();
        custom.setCompany(company);
        custom.setDocumentType("PURCHASE_ORDER");
        custom.setName("My PO");
        custom.setLayout("{}");
        custom.setDefaultTemplate(true);
        documentTemplateRepository.save(custom);

        provisioner.ensureDefaults(company);
        provisioner.ensureDefaults(company); // idempotent

        for (String documentType : schemaRegistry.documentTypes()) {
            DocumentTemplate def = documentTemplateRepository
                    .findByCompanyIdAndDocumentTypeAndDefaultTemplateTrue(company.getId(), documentType)
                    .orElseThrow();
            if (documentType.equals("PURCHASE_ORDER")) {
                assertThat(def.getName()).isEqualTo("My PO");
            } else {
                assertThat(def.getName()).isEqualTo(templateFactory.templateName(documentType));
            }
        }
    }

    private static JsonNode find(JsonNode elements, String id) {
        for (JsonNode el : elements) {
            if (id.equals(el.get("id").asString())) return el;
        }
        throw new AssertionError("No element with id " + id);
    }
}
