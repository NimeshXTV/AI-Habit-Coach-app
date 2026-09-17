package com.habitcoach.profile;

import com.habitcoach.web.InvalidRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Owns the single on-device profile created by onboarding (see UserProfile's
 * javadoc on why there is only ever one row). Re-running onboarding, or any
 * future "edit profile" screen, simply upserts the same row rather than
 * creating a second one.
 */
@Service
public class ProfileService {

    private final UserProfileRepository repository;

    public ProfileService(UserProfileRepository repository) {
        this.repository = repository;
    }

    public Optional<UserProfile> getProfile() {
        return repository.findTopByOrderByIdAsc();
    }

    @Transactional
    public UserProfile saveProfile(String name, Integer age, Gender gender) {
        if (name == null || name.isBlank()) {
            throw new InvalidRequestException("name must not be blank");
        }
        if (age == null || age < 1 || age > 120) {
            throw new InvalidRequestException("age must be between 1 and 120");
        }
        if (gender == null) {
            throw new InvalidRequestException("gender is required");
        }

        UserProfile profile = repository.findTopByOrderByIdAsc().orElseGet(() -> new UserProfile(name, age, gender));
        profile.setName(name.trim());
        profile.setAge(age);
        profile.setGender(gender);
        profile.setUpdatedAt(Instant.now());
        return repository.save(profile);
    }

    /** Clears the profile back to "no profile yet" — GET /api/profile will
     * 404 again afterward, which is what makes onboarding show again on the
     * mobile app's next launch (see App.tsx). Deletes all rows rather than
     * just the one findTopByOrderByIdAsc() would return, as defensive
     * cleanup in case more than one ever exists. */
    @Transactional
    public void deleteProfile() {
        repository.deleteAll();
    }
}
