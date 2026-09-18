package com.habitcoach.profile;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.habitcoach.web.InvalidRequestException;

/**
 * Exercises ProfileService against a real (in-memory, for tests) database —
 * create, retrieve, persistence, upsert, and validation, covering the
 * ONBOARDING/PROFILE test requirements.
 */
@DataJpaTest
@Import(ProfileService.class)
class ProfileServiceTest {

    @Autowired
    private ProfileService profileService;

    @Autowired
    private UserProfileRepository repository;

    @Test
    void noProfileInitially() {
        assertThat(profileService.getProfile("device-1")).isEmpty();
    }

    @Test
    void createsAndRetrievesAProfile() {
        UserProfile saved = profileService.saveProfile("device-1", "Asha", 29, Gender.FEMALE);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getName()).isEqualTo("Asha");
        assertThat(saved.getAge()).isEqualTo(29);
        assertThat(saved.getGender()).isEqualTo(Gender.FEMALE);

        assertThat(profileService.getProfile("device-1")).isPresent();
        assertThat(profileService.getProfile("device-1").get().getName()).isEqualTo("Asha");
    }

    @Test
    void persistsAcrossANewRepositoryLookup() {
        profileService.saveProfile("device-1", "Ravi", 68, Gender.MALE);

        // Reload straight from the repository, not through the same
        // ProfileService instance's in-memory state — proves it actually
        // hit the database, not just a field cache.
        assertThat(repository.findTopByDeviceIdOrderByIdAsc("device-1")).isPresent();
        assertThat(repository.findTopByDeviceIdOrderByIdAsc("device-1").get().getName()).isEqualTo("Ravi");
    }

    @Test
    void savingAgainUpdatesTheSameRowRatherThanCreatingASecondOne() {
        profileService.saveProfile("device-1", "Asha", 29, Gender.FEMALE);
        profileService.saveProfile("device-1", "Asha K.", 30, Gender.FEMALE);

        assertThat(repository.count()).isEqualTo(1);
        assertThat(profileService.getProfile("device-1").get().getName()).isEqualTo("Asha K.");
        assertThat(profileService.getProfile("device-1").get().getAge()).isEqualTo(30);
    }

    @Test
    void blankNameIsRejected() {
        assertThatThrownBy(() -> profileService.saveProfile("device-1", "  ", 20, Gender.OTHER))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void ageOutOfRangeIsRejected() {
        assertThatThrownBy(() -> profileService.saveProfile("device-1", "Kid", 0, Gender.OTHER))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> profileService.saveProfile("device-1", "Old", 121, Gender.OTHER))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void missingGenderIsRejected() {
        assertThatThrownBy(() -> profileService.saveProfile("device-1", "Name", 20, null))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void deleteProfileClearsItBackToNotFound() {
        profileService.saveProfile("device-1", "Asha", 29, Gender.FEMALE);

        profileService.deleteProfile("device-1");

        assertThat(profileService.getProfile("device-1")).isEmpty();
        assertThat(repository.count()).isEqualTo(0);
    }

    @Test
    void deleteProfileWhenNoneExistsIsANoOp() {
        assertThatCode(() -> profileService.deleteProfile("device-1")).doesNotThrowAnyException();
        assertThat(profileService.getProfile("device-1")).isEmpty();
    }

    // ---- device isolation (see CLAUDE_CONTEXT.md's onboarding/data-isolation fix) ----

    @Test
    void aNewDeviceNeverSeesAnotherDevicesProfile() {
        profileService.saveProfile("device-1", "Nimesh", 25, Gender.MALE);

        assertThat(profileService.getProfile("brand-new-device")).isEmpty();
    }

    @Test
    void savingAProfileOnOneDeviceDoesNotAffectAnothersExistingProfile() {
        profileService.saveProfile("device-1", "Nimesh", 25, Gender.MALE);
        profileService.saveProfile("device-2", "Priya", 31, Gender.FEMALE);

        assertThat(profileService.getProfile("device-1").get().getName()).isEqualTo("Nimesh");
        assertThat(profileService.getProfile("device-2").get().getName()).isEqualTo("Priya");
    }

    @Test
    void deletingOneDevicesProfileLeavesAnotherDevicesProfileIntact() {
        profileService.saveProfile("device-1", "Nimesh", 25, Gender.MALE);
        profileService.saveProfile("device-2", "Priya", 31, Gender.FEMALE);

        profileService.deleteProfile("device-1");

        assertThat(profileService.getProfile("device-1")).isEmpty();
        assertThat(profileService.getProfile("device-2")).isPresent();
        assertThat(profileService.getProfile("device-2").get().getName()).isEqualTo("Priya");
    }
}
