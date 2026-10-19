package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.models.Item;
import com.chardizard.Norbiz.repositories.ItemRepository;
import com.chardizard.Norbiz.services.ItemRecognitionService;
import com.chardizard.Norbiz.services.ItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@Tag(name = "Item Images", description = "Upload and serve item images. Images are stored at {ITEM_IMAGE_UPLOAD_DIR}/{companyId}/items/images/{itemCode}.{ext}")
@RestController
@RequiredArgsConstructor
public class ItemImageController {

    private static final Logger log = LoggerFactory.getLogger(ItemImageController.class);

    private final ItemRepository itemRepository;
    private final ItemService itemService;
    private final ItemRecognitionService itemRecognitionService;

    @Value("${app.item-image.upload-dir}")
    private String uploadDir;

    @Operation(summary = "Upload item image", description = "Uploads or replaces the image for an item. " +
            "The file is saved as {itemCode}.{ext} under the company's image directory. Max size: 5 MB.")
    @ApiResponse(responseCode = "200", description = "Image uploaded; returns { imagePath, recognitionIndexed } — "
            + "recognitionIndexed is false when the image couldn't be indexed for photo recognition (embedder down); "
            + "the upload itself still succeeded")
    @ApiResponse(responseCode = "403", description = "Missing UPDATE_ITEM permission or no access to the item's company")
    @ApiResponse(responseCode = "404", description = "Item not found")
    @PostMapping("/items/{id}/image")
    @PreAuthorize("hasAuthority('UPDATE_ITEM')")
    public ResponseEntity<Map<String, Object>> uploadItemImage(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Item ID") @PathVariable Long id,
            @Parameter(description = "Image file (jpg, png, gif, webp)") @RequestParam("file") MultipartFile file) throws IOException {

        // Company-scoped read: UPDATE_ITEM alone must not reach another company's item.
        Item item = itemService.findById(id, userDetails.getUsername());

        String ext = getExtension(file.getOriginalFilename());
        String filename = sanitizeFilename(item.getItemCode()) + ext;

        String companyId = item.getCompany().getId().toString();
        Path itemImageDir = resolveUploadDir().resolve(companyId).resolve("items").resolve("images");
        Files.createDirectories(itemImageDir);

        Path target = itemImageDir.resolve(filename);
        // Prevent path traversal
        if (!target.toAbsolutePath().startsWith(resolveUploadDir().toAbsolutePath())) {
            throw new SecurityException("Invalid file path");
        }

        // Delete old image if present
        if (item.getImagePath() != null) {
            Files.deleteIfExists(resolveUploadDir().resolve(item.getImagePath()));
        }

        // Read once: the same bytes are written to disk and sent to the embedder.
        byte[] bytes = file.getBytes();
        Files.write(target, bytes);

        String imagePath = companyId + "/items/images/" + filename;
        item.setImagePath(imagePath);
        Item saved = itemRepository.save(item);
        log.info("User '{}' uploaded image '{}' for item '{}' (id={})", userDetails.getUsername(), imagePath, item.getItemCode(), id);

        boolean indexed = itemRecognitionService.indexQuietly(saved, bytes);
        return ResponseEntity.ok(Map.of("imagePath", imagePath, "recognitionIndexed", indexed));
    }

    @Operation(summary = "Reindex item images for photo recognition",
            description = "Queues a background job that indexes the company's item images for photo recognition "
                    + "(docs/ITEM_RECOGNITION.md). By default only images not indexed yet; force=true redoes all of them "
                    + "(e.g. after changing the embedder model). Progress is logged; returns the number of items queued.")
    @ApiResponse(responseCode = "202", description = "Job queued; returns { queued }")
    @ApiResponse(responseCode = "400", description = "No company given, or a reindex for this company is already running")
    @ApiResponse(responseCode = "403", description = "Missing UPDATE_ITEM permission or no access to company")
    @ApiResponse(responseCode = "503", description = "Recognition disabled (code ITEM_RECOGNITION_UNAVAILABLE)")
    @PostMapping("/items/image-recognition/reindex")
    @PreAuthorize("hasAuthority('UPDATE_ITEM')")
    public ResponseEntity<AppResponse<Map<String, Integer>>> reindexItemImages(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Company whose images to index; defaults to the X-Company-Id header") @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = "X-Company-Id", required = false) Long headerCompanyId,
            @Parameter(description = "Reindex images that are already indexed too") @RequestParam(defaultValue = "false") boolean force) {
        int queued = itemRecognitionService.reindex(userDetails.getUsername(),
                companyId != null ? companyId : headerCompanyId, force);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(AppResponse.of(Map.of("queued", queued)));
    }

    @Operation(summary = "Serve item image", description = "Serves an item image file. No authentication required — paths are not guessable.")
    @ApiResponse(responseCode = "200", description = "Image file")
    @ApiResponse(responseCode = "404", description = "Image not found")
    @SecurityRequirements
    @GetMapping("/item-images/{companyId}/items/images/{filename}")
    public ResponseEntity<Resource> serveItemImage(
            @Parameter(description = "Company ID") @PathVariable String companyId,
            @Parameter(description = "Filename (itemCode.ext)") @PathVariable String filename) throws MalformedURLException {

        Path file = resolveUploadDir()
                .resolve(companyId)
                .resolve("items")
                .resolve("images")
                .resolve(filename);

        // Prevent path traversal
        if (!file.toAbsolutePath().startsWith(resolveUploadDir().toAbsolutePath())) {
            return ResponseEntity.badRequest().build();
        }

        Resource resource = new UrlResource(file.toUri());
        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(detectContentType(filename)))
                .body(resource);
    }

    private Path resolveUploadDir() {
        return Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    private String sanitizeFilename(String name) {
        // Replace any character that is not alphanumeric, hyphen, or underscore with underscore
        return name.replaceAll("[^a-zA-Z0-9\\-_]", "_");
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "";
        return filename.substring(filename.lastIndexOf('.'));
    }

    private String detectContentType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }
}
