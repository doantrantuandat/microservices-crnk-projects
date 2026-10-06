package io.github.doantrantuandat.example.gateway.config;

import io.crnk.client.CrnkClient;
import io.crnk.client.http.okhttp.OkHttpAdapter;
import io.crnk.client.http.okhttp.OkHttpAdapterListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One CrnkClient per backend. The OkHttp adapter is forced explicitly because this process has no
 * crnk-setup-spring* module on its classpath - unlike every other crnk-client usage in this workspace,
 * CrnkClient.detectHttpAdapter() here can't rely on a co-located server module's presence to pick an
 * adapter; crnk-client-jackson3 ships both OkHttpAdapterProvider and HttpClientAdapterProvider on its own
 * classpath regardless (both registered unconditionally in CrnkClient's constructor), so detection would
 * likely still succeed even without this call, but forcing it removes the ambiguity rather than relying
 * on provider registration order. Verified end-to-end against a live JSON:API server - see
 * CrnkClientSmokeTest.
 */
@Configuration
public class BackendClientsConfig {

    @Bean
    public CrnkClient accountsClient(@Value("${accounts.service.url}") String url) {
        CrnkClient client = new CrnkClient(url);
        client.setHttpAdapter(OkHttpAdapter.newInstance());
        return client;
    }

    @Bean
    public CrnkClient catalogClient(@Value("${catalog.service.url}") String url) {
        CrnkClient client = new CrnkClient(url);
        client.setHttpAdapter(OkHttpAdapter.newInstance());
        return client;
    }

    /**
     * ordering-service's OrderRepositoryDecorator.create()/delete() unconditionally call its own
     * MockAuth.requireAdmin() (a mock/demo auth scheme, not real security - see that project's
     * MockAuth.java), which reads the inbound HTTP request's X-Mock-Role header and throws
     * ForbiddenException unless it's "admin". Nothing else on this client's path would ever set that
     * header, so every create() call this gateway makes against ordering-service would otherwise fail.
     * Attached via an OkHttp interceptor (OkHttpAdapter's own customization hook,
     * addListener(OkHttpAdapterListener)) to every request this client makes, not just creates:
     * ordering-service's reads aren't gated at all (confirmed - no decorator targets them), so the extra
     * header there is harmless, and this avoids per-call-site conditional logic.
     */
    @Bean
    public CrnkClient orderingClient(@Value("${ordering.service.url}") String url) {
        CrnkClient client = new CrnkClient(url);
        OkHttpAdapter adapter = OkHttpAdapter.newInstance();
        adapter.addListener(adminRoleHeaderListener());
        client.setHttpAdapter(adapter);
        return client;
    }

    private static OkHttpAdapterListener adminRoleHeaderListener() {
        return builder -> builder.addInterceptor(chain ->
                chain.proceed(chain.request().newBuilder().header("X-Mock-Role", "admin").build()));
    }
}
