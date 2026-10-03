package com.chardizard.Norbiz.cache;

import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Wires the Redis query cache — see docs/CACHING.md. */
@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {

    /**
     * Registers {@link CacheInvalidationListener} with Hibernate through an Integrator, since in
     * Hibernate 7 event listeners must be in place while the SessionFactory is being built.
     * Skipped entirely when caching is disabled (e.g. tests), so no Redis call is ever attempted.
     */
    @Bean
    public HibernatePropertiesCustomizer cacheInvalidationIntegrator(CacheProperties properties, GenerationStore generations) {
        return hibernateProperties -> {
            if (!properties.isEnabled()) return;
            CacheInvalidationListener listener = new CacheInvalidationListener(generations);
            Integrator integrator = new Integrator() {
                @Override
                public void integrate(org.hibernate.boot.Metadata metadata,
                                      org.hibernate.boot.spi.BootstrapContext bootstrapContext,
                                      org.hibernate.engine.spi.SessionFactoryImplementor sessionFactory) {
                    EventListenerRegistry registry = sessionFactory.getEventListenerRegistry();
                    registry.appendListeners(EventType.POST_INSERT, listener);
                    registry.appendListeners(EventType.POST_UPDATE, listener);
                    registry.appendListeners(EventType.POST_DELETE, listener);
                    registry.appendListeners(EventType.POST_COLLECTION_RECREATE, listener);
                    registry.appendListeners(EventType.POST_COLLECTION_UPDATE, listener);
                    registry.appendListeners(EventType.POST_COLLECTION_REMOVE, listener);
                }
            };
            hibernateProperties.put("hibernate.integrator_provider", (IntegratorProvider) () -> List.of(integrator));
        };
    }
}
