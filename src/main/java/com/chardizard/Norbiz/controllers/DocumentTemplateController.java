package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.DocumentSchemaResponse;
import com.chardizard.Norbiz.dto.DocumentTemplateRequest;
import com.chardizard.Norbiz.dto.DocumentTemplateResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.models.DocumentTemplate;
import com.chardizard.Norbiz.services.DocumentSchemaRegistry;
import com.chardizard.Norbiz.services.DocumentTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "Document Templates", description = "Printable document template designer — requires the single MANAGE_DOCUMENT_TEMPLATES permission " +
        "for all operations (designing templates and fetching them to print).")
@RestController
@RequestMapping("/document-templates")
@RequiredArgsConstructor
public class DocumentTemplateController {

    private final DocumentTemplateService documentTemplateService;
    private final DocumentSchemaRegistry documentSchemaRegistry;

    @Operation(summary = "List document templates", description = "Returns templates belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "Template list returned")
    @ApiResponse(responseCode = "403", description = "Missing MANAGE_DOCUMENT_TEMPLATES permission")
    @GetMapping
    @PreAuthorize("hasAuthority('MANAGE_DOCUMENT_TEMPLATES')")
    public ResponseEntity<AppResponse<PageResponse<DocumentTemplateResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by name (contains)") @RequestParam(required = false) String name,
            @Parameter(description = "Filter by document type (contains)") @RequestParam(required = false) String documentType,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(name)) filters.put("name", name);
        if (StringUtils.hasText(documentType)) filters.put("documentType", documentType);

        var templates = documentTemplateService.findAllForUser(userDetails.getUsername(), filters, pageable).map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(templates)));
    }

    @Operation(summary = "Get document template by ID")
    @ApiResponse(responseCode = "200", description = "Template returned")
    @ApiResponse(responseCode = "403", description = "Missing MANAGE_DOCUMENT_TEMPLATES permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Template not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MANAGE_DOCUMENT_TEMPLATES')")
    public ResponseEntity<AppResponse<DocumentTemplateResponse>> getById(@Parameter(description = "Template ID") @PathVariable Long id,
                                                                         @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(documentTemplateService.findById(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Get the default template for a document type", description = "Used by the print flow to find which template to render with.")
    @ApiResponse(responseCode = "200", description = "Default template returned")
    @ApiResponse(responseCode = "400", description = "No default template configured for this document type")
    @ApiResponse(responseCode = "403", description = "Missing MANAGE_DOCUMENT_TEMPLATES permission or no access to company")
    @GetMapping("/default")
    @PreAuthorize("hasAuthority('MANAGE_DOCUMENT_TEMPLATES')")
    public ResponseEntity<AppResponse<DocumentTemplateResponse>> getDefault(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Company ID") @RequestParam Long companyId,
            @Parameter(description = "Document type, e.g. INVENTORY_ADJUSTMENT") @RequestParam String documentType) {
        return ResponseEntity.ok(AppResponse.of(toResponse(documentTemplateService.findDefault(userDetails.getUsername(), companyId, documentType))));
    }

    @Operation(summary = "Get the bindable field schema for a document type", description = "Powers the designer's field palette.")
    @ApiResponse(responseCode = "200", description = "Schema returned")
    @ApiResponse(responseCode = "400", description = "Unknown document type")
    @ApiResponse(responseCode = "403", description = "Missing MANAGE_DOCUMENT_TEMPLATES permission")
    @GetMapping("/schema")
    @PreAuthorize("hasAuthority('MANAGE_DOCUMENT_TEMPLATES')")
    public ResponseEntity<AppResponse<DocumentSchemaResponse>> getSchema(
            @Parameter(description = "Document type, e.g. INVENTORY_ADJUSTMENT") @RequestParam String documentType) {
        return ResponseEntity.ok(AppResponse.of(documentSchemaRegistry.getSchema(documentType)));
    }

    @Operation(summary = "Create document template")
    @ApiResponse(responseCode = "201", description = "Template created")
    @ApiResponse(responseCode = "403", description = "Missing MANAGE_DOCUMENT_TEMPLATES permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('MANAGE_DOCUMENT_TEMPLATES')")
    public ResponseEntity<AppResponse<DocumentTemplateResponse>> create(@Valid @RequestBody DocumentTemplateRequest request,
                                                                        @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(documentTemplateService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Update document template")
    @ApiResponse(responseCode = "200", description = "Template updated")
    @ApiResponse(responseCode = "403", description = "Missing MANAGE_DOCUMENT_TEMPLATES permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Template not found")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MANAGE_DOCUMENT_TEMPLATES')")
    public ResponseEntity<AppResponse<DocumentTemplateResponse>> update(@Parameter(description = "Template ID") @PathVariable Long id,
                                                                        @Valid @RequestBody DocumentTemplateRequest request,
                                                                        @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(documentTemplateService.update(id, request, userDetails.getUsername()))));
    }

    @Operation(summary = "Delete document template")
    @ApiResponse(responseCode = "204", description = "Template deleted")
    @ApiResponse(responseCode = "403", description = "Missing MANAGE_DOCUMENT_TEMPLATES permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Template not found")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('MANAGE_DOCUMENT_TEMPLATES')")
    public ResponseEntity<Void> delete(@Parameter(description = "Template ID") @PathVariable Long id,
                                       @AuthenticationPrincipal UserDetails userDetails) {
        documentTemplateService.delete(id, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    private DocumentTemplateResponse toResponse(DocumentTemplate template) {
        DocumentTemplateResponse res = new DocumentTemplateResponse();
        res.setId(template.getId());
        res.setCompanyId(template.getCompany().getId());
        res.setCompanyName(template.getCompany().getName());
        res.setDocumentType(template.getDocumentType());
        res.setName(template.getName());
        res.setLayout(template.getLayout());
        res.setDefaultTemplate(template.isDefaultTemplate());
        res.setActive(template.isActive());
        res.setCreatedAt(template.getCreatedAt());
        res.setUpdatedAt(template.getUpdatedAt());
        res.setCreatedBy(template.getCreatedBy());
        res.setUpdatedBy(template.getUpdatedBy());
        return res;
    }
}
