package io.github.doantrantuandat.example.accounts;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;

// Seed id 1 is a deliberate cross-task convention, not an incidental choice: the later integration task's
// verify.ps1 exercises ?include=orders against this account id, and ordering-service's own seed data is
// expected to point at least one Order back at this same id 1.
@Component
public class AccountDataInitializer implements ApplicationRunner {

    @Autowired
    private EntityManager entityManager;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (entityManager.find(Account.class, 1L) == null) {
            entityManager.persist(Account.builder().id(1L).name("Alice Nguyen").email("alice@example.com").plan("pro").build());
        }
        if (entityManager.find(Account.class, 2L) == null) {
            entityManager.persist(Account.builder().id(2L).name("Bob Tran").email("bob@example.com").plan("free").build());
        }
        if (entityManager.find(Account.class, 3L) == null) {
            entityManager.persist(Account.builder().id(3L).name("Carol Pham").email("carol@example.com").plan("free").build());
        }
    }
}
