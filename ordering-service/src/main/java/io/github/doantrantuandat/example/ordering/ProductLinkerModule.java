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

// Forward, by-id-only, no lookup method, no decorator population - same pattern as AccountLinkerModule.
// Decorator-factory registration lives on AccountLinkerModule (one registration site for this service).
@Component
public class ProductLinkerModule implements Module {

    private final ResourceRepository<Product, Serializable> remoteProductRepository;

    public ProductLinkerModule(@Value("${catalog.service.url}") String catalogServiceUrl) {
        CrnkClient client = new CrnkClient(catalogServiceUrl);
        this.remoteProductRepository = client.getRepositoryForType(Product.class);
    }

    @Override
    public String getModuleName() { return "productLink"; }

    @Override
    public void setupModule(ModuleContext context) {
        context.addRepository(new RemoteProductRepository<>(remoteProductRepository));
    }

    @JsonApiExposed(false)
    static class RemoteProductRepository<T, I> extends WrappedResourceRepository<T, I> {
        RemoteProductRepository(ResourceRepository<T, I> remote) { super(remote); }

        @Override
        public T findOne(I id, QuerySpec qs) {
            try { return super.findOne(id, qs); }
            catch (RuntimeException e) {
                System.err.println("WARN: catalog-service unreachable resolving product " + id + ": " + e.getMessage());
                throw new ResourceNotFoundException("remote resource unavailable: " + id);
            }
        }
        @Override
        public ResourceList<T> findAll(QuerySpec qs) {
            try { return super.findAll(qs); }
            catch (RuntimeException e) {
                System.err.println("WARN: catalog-service unreachable (findAll): " + e.getMessage());
                return new io.crnk.core.resource.list.DefaultResourceList<>();
            }
        }
        @Override
        public ResourceList<T> findAll(Collection<I> ids, QuerySpec qs) {
            try { return super.findAll(ids, qs); }
            catch (RuntimeException e) {
                System.err.println("WARN: catalog-service unreachable (findAll by ids): " + e.getMessage());
                return new io.crnk.core.resource.list.DefaultResourceList<>();
            }
        }
    }
}
