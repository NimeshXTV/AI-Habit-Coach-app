package com.habitcoach.profile;

import com.habitcoach.web.NotFoundException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Onboarding's backend half. GET 404s until a profile has been saved once —
 * mobile uses that 404 as the "show onboarding" signal on launch (see
 * App.tsx). POST is an upsert: onboarding is a one-time flow today, but
 * saving twice must never create a second row (see ProfileService). DELETE
 * clears it back to that "no profile yet" state — the only way to make a
 * device see onboarding again without editing the database directly, e.g.
 * to clear stale data left over from development/testing.
 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public UserProfile getProfile() {
        return profileService.getProfile().orElseThrow(() -> new NotFoundException("profile not found"));
    }

    @PostMapping
    public UserProfile saveProfile(@Valid @RequestBody ProfileRequest body) {
        return profileService.saveProfile(body.name(), body.age(), body.gender());
    }

    @DeleteMapping
    public Map<String, Boolean> deleteProfile() {
        profileService.deleteProfile();
        return Map.of("ok", true);
    }
}
