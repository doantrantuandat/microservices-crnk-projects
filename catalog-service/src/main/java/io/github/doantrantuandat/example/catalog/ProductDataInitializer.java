package io.github.doantrantuandat.example.catalog;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;

// accountId 1 matches accounts-service's seeded account id 1 - the cross-task "primary seeded account"
// convention this workspace uses (see accounts-service's AccountDataInitializer).
@Component
public class ProductDataInitializer implements ApplicationRunner {

    @Autowired
    private EntityManager entityManager;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (entityManager.find(Product.class, 1L) == null) {
            entityManager.persist(Product.builder().id(1L).sku("SKU-001").name("Wireless Mouse")
                    .price(new BigDecimal("19.99")).accountId(1L).build());
        }
        if (entityManager.find(Product.class, 2L) == null) {
            entityManager.persist(Product.builder().id(2L).sku("SKU-002").name("Mechanical Keyboard")
                    .price(new BigDecimal("89.50")).accountId(1L).build());
        }
        if (entityManager.find(Product.class, 3L) == null) {
            entityManager.persist(Product.builder().id(3L).sku("SKU-003").name("USB-C Hub")
                    .price(new BigDecimal("34.00")).accountId(1L).build());
        }
    }
}
