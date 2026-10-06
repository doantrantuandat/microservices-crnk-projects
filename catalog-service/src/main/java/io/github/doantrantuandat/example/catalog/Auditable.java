package io.github.doantrantuandat.example.catalog;

import lombok.Getter;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Instant;

@Getter
@MappedSuperclass
public abstract class Auditable {

    private Instant createdAt;

    private Instant updatedAt;

    private String createdBy;

    private String updatedBy;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
        createdBy = MockAuth.currentUser();
        updatedBy = createdBy;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        updatedBy = MockAuth.currentUser();
    }
}
