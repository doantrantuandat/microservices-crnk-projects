package io.github.doantrantuandat.example.accounts;

import io.crnk.client.CrnkClient;
import io.crnk.client.http.okhttp.OkHttpAdapter;
import io.crnk.core.exception.ResourceNotFoundException;
import io.crnk.core.module.Module;
import io.crnk.core.queryspec.FilterOperator;
import io.crnk.core.queryspec.FilterSpec;
import io.crnk.core.queryspec.PathSpec;
import io.crnk.core.queryspec.QuerySpec;
import io.crnk.core.repository.ResourceRepository;
import io.crnk.core.repository.decorate.WrappedResourceRepository;
import io.crnk.core.resource.annotations.JsonApiExposed;
import io.crnk.core.resource.list.ResourceList;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

// Deliberately NOT @Component: Spring 5.2/Boot 2.3.12's bundled ASM cannot parse class files compiled
// at JDK21 bytecode level when component-scanning walks this class's "implements Module" interface
// chain. Register via an explicit @Bean factory method instead (see AccountsServiceApplication).
public class OrderLinkerModule implements Module {

    private final ResourceRepository<Order, Serializable> remoteOrderRepository;

    public OrderLinkerModule(String orderingServiceUrl) {
        CrnkClient client = new CrnkClient(orderingServiceUrl);
        // Force OkHttp explicitly: with spring-boot-starter-web on this app's classpath, crnk-client
        // would auto-discover crnk-setup-spring's RestTemplate-based adapter, which is compiled against
        // a newer Spring than this service's Spring 5.2 - a real NoSuchMethodError at runtime, not a
        // hypothetical one.
        client.setHttpAdapter(OkHttpAdapter.newInstance());
        this.remoteOrderRepository = client.getRepositoryForType(Order.class);
    }

    @Override
    public String getModuleName() {
        return "orderLink";
    }

    @Override
    @SuppressWarnings("unchecked")
    public void setupModule(ModuleContext context) {
        context.addRepository(new RemoteOrderRepository<>(remoteOrderRepository));
        // Merged in here rather than a separate AccountBusinessRulesModule (per-relation split isn't
        // warranted - this service has exactly one relation).
        context.addRepositoryDecoratorFactory(repository -> {
            if (repository instanceof ResourceRepository
                    && ((ResourceRepository<?, ?>) repository).getResourceClass() == Account.class) {
                return new AccountRepositoryDecorator((ResourceRepository<Account, Long>) repository, this);
            }
            return repository;
        });
    }

    /** Fails open (returns empty, doesn't throw) if ordering-service can't be reached. */
    public List<Long> findOrderIdsForAccount(Long accountId) {
        try {
            QuerySpec querySpec = new QuerySpec(Order.class);
            querySpec.addFilter(new FilterSpec(PathSpec.of("account.id"), FilterOperator.EQ, accountId));
            ResourceList<Order> orders = remoteOrderRepository.findAll(querySpec);
            return orders.stream().map(Order::getId).collect(Collectors.toList());
        } catch (RuntimeException e) {
            System.err.println("WARN: could not look up orders for account " + accountId
                    + " (ordering-service unreachable): " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // Registered so crnk's own include-engine can fetch "included" Order representations when a
    // caller requests ?include=orders - not just for this class's own lookup method above. This
    // workspace closes a gap the library's own reference demo left open: ITS forward-relation remote
    // wrappers never catch anything, only its reverse-lookup methods fail open. Here, fail-open
    // applies uniformly to both paths.
    @JsonApiExposed(false)
    static class RemoteOrderRepository<T, I> extends WrappedResourceRepository<T, I> {

        RemoteOrderRepository(ResourceRepository<T, I> remoteRepository) {
            super(remoteRepository);
        }

        @Override
        public T findOne(I id, QuerySpec querySpec) {
            try {
                return super.findOne(id, querySpec);
            } catch (RuntimeException e) {
                System.err.println("WARN: ordering-service unreachable resolving order " + id + ": " + e.getMessage());
                throw new ResourceNotFoundException("remote resource unavailable: " + id);
            }
        }

        @Override
        public ResourceList<T> findAll(QuerySpec querySpec) {
            try {
                return super.findAll(querySpec);
            } catch (RuntimeException e) {
                System.err.println("WARN: ordering-service unreachable (findAll): " + e.getMessage());
                return new io.crnk.core.resource.list.DefaultResourceList<>();
            }
        }

        @Override
        public ResourceList<T> findAll(Collection<I> ids, QuerySpec querySpec) {
            try {
                return super.findAll(ids, querySpec);
            } catch (RuntimeException e) {
                System.err.println("WARN: ordering-service unreachable (findAll by ids): " + e.getMessage());
                return new io.crnk.core.resource.list.DefaultResourceList<>();
            }
        }
    }
}
