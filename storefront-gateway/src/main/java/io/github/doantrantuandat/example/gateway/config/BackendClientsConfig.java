package io.github.doantrantuandat.example.gateway.config;

import io.crnk.client.CrnkClient;
import io.crnk.client.http.okhttp.OkHttpAdapter;
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

    @Bean
    public CrnkClient orderingClient(@Value("${ordering.service.url}") String url) {
        CrnkClient client = new CrnkClient(url);
        client.setHttpAdapter(OkHttpAdapter.newInstance());
        return client;
    }
}
