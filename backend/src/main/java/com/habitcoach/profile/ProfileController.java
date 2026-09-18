package com.habitcoach.profile;

import com.habitcoach.web.NotFoundException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Onboarding's backend half. Every route requires the X-Device-Id header
 * (see mobile's deviceId.ts/api.ts) and only ever reads/writes that one
 * device's row — this is what stops a fresh install/different device from
 * ever seeing another device's profile. GET 404s until a profile has been
 * saved once for that deviceId — mobile uses that 404 as the "show
 * onboarding" signal on launch (see App.tsx). POST is an upsert:
 * onboarding is a one-time flow today, but saving twice must never create
 * a second row for the same device (see ProfileService). DELETE clears
 * this device's row back to that "no profile yet" state — the only way to
 * make a device see onboarding again without editing the database
 * directly, e.g. to clear stale data left over from development/testing.
 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public UserProfile getProfile(@RequestHeader("X-Device-Id") String deviceId) {
        return profileService.getProfile(deviceId).orElseThrow(() -> new NotFoundException("profile not found"));
    }

    @PostMapping
    public UserProfile saveProfile(@RequestHeader("X-Device-Id") String deviceId, @Valid @RequestBody ProfileRequest body) {
        return profileService.saveProfile(deviceId, body.name(), body.age(), body.gender());
    }

    @DeleteMapping
    public Map<String, Boolean> deleteProfile(@RequestHeader("X-Device-Id") String deviceId) {
        profileService.deleteProfile(deviceId);
        return Map.of("ok", true);
    }
}
