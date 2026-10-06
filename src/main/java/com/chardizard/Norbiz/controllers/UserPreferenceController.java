package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.UserPreferenceRequest;
import com.chardizard.Norbiz.dto.UserPreferenceResponse;
import com.chardizard.Norbiz.models.UserPreference;
import com.chardizard.Norbiz.services.UserPreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

// No permission gate: every authenticated user may keep their own preferences, and the service
// only ever touches the caller's rows.
@Tag(name = "User Preferences", description = "The current user's own UI preferences (e.g. saved list column layouts)")
@RestController
@RequestMapping("/me/preferences")
@RequiredArgsConstructor
public class UserPreferenceController {

    private static final String KEY_PATTERN = "^[A-Za-z0-9][A-Za-z0-9:._-]*$";
    private static final String KEY_MESSAGE = "must contain only letters, digits, ':', '.', '_' or '-'";

    private final UserPreferenceService userPreferenceService;

    @Operation(summary = "Get a preference", description = "Returns the caller's saved value for `key`, or `data: null` when they haven't saved one.")
    @ApiResponse(responseCode = "200", description = "Preference returned (data is null when unset)")
    @GetMapping("/{key}")
    public ResponseEntity<AppResponse<UserPreferenceResponse>> get(
            @PathVariable @Size(max = 100) @Pattern(regexp = KEY_PATTERN, message = KEY_MESSAGE) String key,
            @AuthenticationPrincipal UserDetails userDetails) {
        UserPreferenceResponse response = userPreferenceService.find(userDetails.getUsername(), key)
                .map(this::toResponse)
                .orElse(null);
        return ResponseEntity.ok(AppResponse.of(response));
    }

    @Operation(summary = "Save a preference", description = "Creates or replaces the caller's value for `key`. The value is opaque JSON owned by the frontend.")
    @ApiResponse(responseCode = "200", description = "Preference saved")
    @ApiResponse(responseCode = "400", description = "Invalid key or value")
    @PutMapping("/{key}")
    public ResponseEntity<AppResponse<UserPreferenceResponse>> save(
            @PathVariable @Size(max = 100) @Pattern(regexp = KEY_PATTERN, message = KEY_MESSAGE) String key,
            @Valid @RequestBody UserPreferenceRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        UserPreference saved = userPreferenceService.save(userDetails.getUsername(), key, request.getValue());
        return ResponseEntity.ok(AppResponse.of(toResponse(saved)));
    }

    @Operation(summary = "Clear a preference", description = "Deletes the caller's value for `key`, restoring the frontend default. A no-op when unset.")
    @ApiResponse(responseCode = "200", description = "Preference cleared")
    @DeleteMapping("/{key}")
    public ResponseEntity<AppResponse<String>> delete(
            @PathVariable @Size(max = 100) @Pattern(regexp = KEY_PATTERN, message = KEY_MESSAGE) String key,
            @AuthenticationPrincipal UserDetails userDetails) {
        userPreferenceService.delete(userDetails.getUsername(), key);
        return ResponseEntity.ok(AppResponse.of("Preference cleared."));
    }

    private UserPreferenceResponse toResponse(UserPreference p) {
        return new UserPreferenceResponse(p.getPrefKey(), p.getValue(), p.getUpdatedAt());
    }
}
