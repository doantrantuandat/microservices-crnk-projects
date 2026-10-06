package io.github.doantrantuandat.example.accounts;

import lombok.Getter;

import javax.persistence.MappedSuperclass;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
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
