package com.habitcoach.profile;

import com.habitcoach.web.InvalidRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Owns the on-device profile created by onboarding, scoped by deviceId (see
 * UserProfile's javadoc on why there is only ever one row per device).
 * Re-running onboarding, or any future "edit profile" screen, simply
 * upserts the same device's row rather than creating a second one for it —
 * and never touches any other device's row.
 */
@Service
public class ProfileService {

    private final UserProfileRepository repository;

    public ProfileService(UserProfileRepository repository) {
        this.repository = repository;
    }

    public Optional<UserProfile> getProfile(String deviceId) {
        return repository.findTopByDeviceIdOrderByIdAsc(deviceId);
    }

    @Transactional
    public UserProfile saveProfile(String deviceId, String name, Integer age, Gender gender) {
        if (name == null || name.isBlank()) {
            throw new InvalidRequestException("name must not be blank");
        }
        if (age == null || age < 1 || age > 120) {
            throw new InvalidRequestException("age must be between 1 and 120");
        }
        if (gender == null) {
            throw new InvalidRequestException("gender is required");
        }

        UserProfile profile = repository.findTopByDeviceIdOrderByIdAsc(deviceId)
                .orElseGet(() -> new UserProfile(deviceId, name, age, gender));
        profile.setName(name.trim());
        profile.setAge(age);
        profile.setGender(gender);
        profile.setUpdatedAt(Instant.now());
        return repository.save(profile);
    }

    /** Clears this device's profile back to "no profile yet" — GET
     * /api/profile will 404 again afterward for this same deviceId, which
     * is what makes onboarding show again on the mobile app's next launch
     * (see App.tsx). Never touches another device's row. */
    @Transactional
    public void deleteProfile(String deviceId) {
        repository.deleteByDeviceId(deviceId);
    }
}
