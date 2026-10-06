package io.github.doantrantuandat.example.ordering;

import io.crnk.core.repository.ResourceRepository;
import io.crnk.core.repository.decorate.WrappedResourceRepository;

public class OrderRepositoryDecorator extends WrappedResourceRepository<Order, Long> {

    public OrderRepositoryDecorator(ResourceRepository<Order, Long> wrapped) {
        super(wrapped);
    }

    @Override
    public <S extends Order> S create(S order) {
        MockAuth.requireAdmin("create a new order");
        return super.create(order);
    }

    @Override
    public void delete(Long id) {
        MockAuth.requireAdmin("remove an order");
        super.delete(id);
    }
}
