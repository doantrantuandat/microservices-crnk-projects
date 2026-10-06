package io.github.doantrantuandat.example.ordering;

import io.crnk.client.CrnkClient;
import io.crnk.core.exception.ResourceNotFoundException;
import io.crnk.core.module.Module;
import io.crnk.core.queryspec.QuerySpec;
import io.crnk.core.repository.ResourceRepository;
import io.crnk.core.repository.decorate.WrappedResourceRepository;
import io.crnk.core.resource.annotations.JsonApiExposed;
import io.crnk.core.resource.list.ResourceList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.Collection;

// @Component works fine here (Boot4/Spring 7-line component-scan, no ASM trap like the Boot2 project's
// OrderLinkerModule) - @Value resolves the constructor param directly.
//
// Also registers OrderRepositoryDecorator's decorator-factory (the only such registration site in this
// service, per the brief's "keep it to one registration site" instruction) - merged in here rather than
// ProductLinkerModule since Order.account is the entity's "primary" forward relation.
@Component
public class AccountLinkerModule implements Module {

    private final ResourceRepository<Account, Serializable> remoteAccountRepository;

    public AccountLinkerModule(@Value("${accounts.service.url}") String accountsServiceUrl) {
        CrnkClient client = new CrnkClient(accountsServiceUrl);
        this.remoteAccountRepository = client.getRepositoryForType(Account.class);
    }

    @Override
    public String getModuleName() { return "accountLink"; }

    @Override
    @SuppressWarnings("unchecked")
    public void setupModule(ModuleContext context) {
        context.addRepository(new RemoteAccountRepository<>(remoteAccountRepository));
        context.addRepositoryDecoratorFactory(repository -> {
            if (repository instanceof ResourceRepository
                    && ((ResourceRepository<?, ?>) repository).getResourceClass() == Order.class) {
                return new OrderRepositoryDecorator((ResourceRepository<Order, Long>) repository);
            }
            return repository;
        });
    }

    @JsonApiExposed(false)
    static class RemoteAccountRepository<T, I> extends WrappedResourceRepository<T, I> {
        RemoteAccountRepository(ResourceRepository<T, I> remote) { super(remote); }

        @Override
        public T findOne(I id, QuerySpec qs) {
            try { return super.findOne(id, qs); }
            catch (RuntimeException e) {
                System.err.println("WARN: accounts-service unreachable resolving account " + id + ": " + e.getMessage());
                throw new ResourceNotFoundException("remote resource unavailable: " + id);
            }
        }
        @Override
        public ResourceList<T> findAll(QuerySpec qs) {
            try { return super.findAll(qs); }
            catch (RuntimeException e) {
                System.err.println("WARN: accounts-service unreachable (findAll): " + e.getMessage());
                return new io.crnk.core.resource.list.DefaultResourceList<>();
            }
        }
        @Override
        public ResourceList<T> findAll(Collection<I> ids, QuerySpec qs) {
            try { return super.findAll(ids, qs); }
            catch (RuntimeException e) {
                System.err.println("WARN: accounts-service unreachable (findAll by ids): " + e.getMessage());
                return new io.crnk.core.resource.list.DefaultResourceList<>();
            }
        }
    }
}
