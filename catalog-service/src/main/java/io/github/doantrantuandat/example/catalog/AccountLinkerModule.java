package io.github.doantrantuandat.example.catalog;

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

// @Component works fine here (no ASM/component-scan trap on this generation, unlike the Boot2 project's
// OrderLinkerModule) - registered via normal component-scan, @Value resolves the constructor param
// directly without needing an explicit @Bean factory method.
@Component
public class AccountLinkerModule implements Module {

    private final ResourceRepository<Account, Serializable> remoteAccountRepository;

    public AccountLinkerModule(@Value("${accounts.service.url}") String accountsServiceUrl) {
        // No forced HTTP adapter here (unlike the Boot2 project) - this process has crnk-setup-spring
        // on its own classpath, same generation as this app, so auto-detection is safe.
        CrnkClient client = new CrnkClient(accountsServiceUrl);
        this.remoteAccountRepository = client.getRepositoryForType(Account.class);
    }

    @Override
    public String getModuleName() {
        return "accountLink";
    }

    @Override
    @SuppressWarnings("unchecked")
    public void setupModule(ModuleContext context) {
        context.addRepository(new RemoteAccountRepository<>(remoteAccountRepository));
        // Merged in here rather than a separate module class (per-relation split isn't warranted - this
        // service has exactly one relation), matching accounts-service's OrderLinkerModule pattern.
        // No lookup method / decorator-side population needed (unlike OrderLinkerModule): Product.owner
        // is resolved purely by id, so crnk's own ?include= resolution calls
        // RemoteAccountRepository#findOne automatically via the registered repository above.
        context.addRepositoryDecoratorFactory(repository -> {
            if (repository instanceof ResourceRepository
                    && ((ResourceRepository<?, ?>) repository).getResourceClass() == Product.class) {
                return new ProductRepositoryDecorator((ResourceRepository<Product, Long>) repository);
            }
            return repository;
        });
    }

    // Fail-open wrapper: a dead accounts-service must degrade ?include=owner (and crnk's own
    // relation-resolution findOne calls) to a clean 404, never a raw 500/timeout.
    @JsonApiExposed(false)
    static class RemoteAccountRepository<T, I> extends WrappedResourceRepository<T, I> {

        RemoteAccountRepository(ResourceRepository<T, I> remoteRepository) {
            super(remoteRepository);
        }

        @Override
        public T findOne(I id, QuerySpec querySpec) {
            try {
                return super.findOne(id, querySpec);
            } catch (RuntimeException e) {
                System.err.println("WARN: accounts-service unreachable resolving account " + id + ": " + e.getMessage());
                throw new ResourceNotFoundException("remote resource unavailable: " + id);
            }
        }

        @Override
        public ResourceList<T> findAll(QuerySpec querySpec) {
            try {
                return super.findAll(querySpec);
            } catch (RuntimeException e) {
                System.err.println("WARN: accounts-service unreachable (findAll): " + e.getMessage());
                return new io.crnk.core.resource.list.DefaultResourceList<>();
            }
        }

        @Override
        public ResourceList<T> findAll(Collection<I> ids, QuerySpec querySpec) {
            try {
                return super.findAll(ids, querySpec);
            } catch (RuntimeException e) {
                System.err.println("WARN: accounts-service unreachable (findAll by ids): " + e.getMessage());
                return new io.crnk.core.resource.list.DefaultResourceList<>();
            }
        }
    }
}
