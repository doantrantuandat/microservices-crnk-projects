package io.github.doantrantuandat.example.accounts;

import io.crnk.core.queryspec.QuerySpec;
import io.crnk.core.repository.ResourceRepository;
import io.crnk.core.repository.decorate.WrappedResourceRepository;
import io.crnk.core.resource.list.ResourceList;

import java.util.Collection;

public class AccountRepositoryDecorator extends WrappedResourceRepository<Account, Long> {

    private final OrderLinkerModule orderLinker;

    public AccountRepositoryDecorator(ResourceRepository<Account, Long> wrapped, OrderLinkerModule orderLinker) {
        super(wrapped);
        this.orderLinker = orderLinker;
    }

    @Override
    public Account findOne(Long id, QuerySpec querySpec) {
        Account account = super.findOne(id, querySpec);
        populateOrderIds(account);
        return account;
    }

    @Override
    public ResourceList<Account> findAll(QuerySpec querySpec) {
        ResourceList<Account> accounts = super.findAll(querySpec);
        accounts.forEach(this::populateOrderIds);
        return accounts;
    }

    @Override
    public ResourceList<Account> findAll(Collection<Long> ids, QuerySpec querySpec) {
        ResourceList<Account> accounts = super.findAll(ids, querySpec);
        accounts.forEach(this::populateOrderIds);
        return accounts;
    }

    @Override
    public <S extends Account> S create(S account) {
        MockAuth.requireAdmin("onboard a new account");
        return super.create(account);
    }

    @Override
    public void delete(Long id) {
        MockAuth.requireAdmin("remove an account");
        super.delete(id);
    }

    private void populateOrderIds(Account account) {
        if (account != null) {
            account.setOrderIds(orderLinker.findOrderIdsForAccount(account.getId()));
        }
    }
}
