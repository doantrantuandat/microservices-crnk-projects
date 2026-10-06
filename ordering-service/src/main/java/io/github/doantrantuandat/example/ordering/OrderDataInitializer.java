package io.github.doantrantuandat.example.ordering;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

// Order id 1 / accountId 1 matches accounts-service's seeded account id 1; the line's productId 1 matches
// catalog-service's seeded product id 1 - the cross-task "primary seeded id" convention this workspace uses
// (see accounts-service's AccountDataInitializer / catalog-service's ProductDataInitializer). Persisted
// directly via EntityManager (not through Order.lines' cascade) - OrderLine is its own top-level crnk
// resource regardless, so there's no need to round-trip through the parent's cascade for seed data.
@Component
public class OrderDataInitializer implements ApplicationRunner {

    @Autowired
    private EntityManager entityManager;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (entityManager.find(Order.class, 1L) == null) {
            entityManager.persist(Order.builder().id(1L).orderNumber("ORD-1001").accountId(1L).build());
        }
        if (entityManager.find(OrderLine.class, 1L) == null) {
            entityManager.persist(OrderLine.builder().id(1L).orderId(1L).productId(1L).qty(2).build());
        }
    }
}
