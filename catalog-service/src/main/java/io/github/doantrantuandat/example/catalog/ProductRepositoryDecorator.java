package io.github.doantrantuandat.example.catalog;

import io.crnk.core.repository.ResourceRepository;
import io.crnk.core.repository.decorate.WrappedResourceRepository;

public class ProductRepositoryDecorator extends WrappedResourceRepository<Product, Long> {

    public ProductRepositoryDecorator(ResourceRepository<Product, Long> wrapped) {
        super(wrapped);
    }

    @Override
    public <S extends Product> S create(S product) {
        MockAuth.requireAdmin("add a new product");
        return super.create(product);
    }

    @Override
    public void delete(Long id) {
        MockAuth.requireAdmin("remove a product");
        super.delete(id);
    }
}
