package dev.sonti.offers;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@Profile("postgres-local & !local-demo")
@ConditionalOnProperty(name = "offers.search.enabled", havingValue = "true")
class SearchConfiguration {
    @Bean(destroyMethod = "close")
    OpenSearchIndex offerIndex(@Value("${offers.search.endpoint}") String endpoint,
            @Value("${offers.search.index}") String index, JsonMapper json) {
        return new OpenSearchIndex(endpoint, index, json);
    }
    @Bean OfferSearch offerSearch(OpenSearchIndex index, OfferCatalog catalog, Clock clock) {
        return new OfferSearch(index::candidates, catalog, clock);
    }
}
