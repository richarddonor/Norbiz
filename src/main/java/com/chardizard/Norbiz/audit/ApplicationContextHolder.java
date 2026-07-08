package com.chardizard.Norbiz.audit;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

/**
 * Static accessor for the Spring ApplicationContext, used by JPA entity listeners
 * which are instantiated by the JPA provider (not Spring) and therefore cannot use @Autowired.
 * Context is set only after full refresh so all beans are guaranteed to be available.
 */
@Component
public class ApplicationContextHolder implements ApplicationListener<ContextRefreshedEvent> {

    private static volatile ApplicationContext context;

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        context = event.getApplicationContext();
    }

    public static <T> T getBean(Class<T> beanClass) {
        if (context == null) return null;
        return context.getBeanProvider(beanClass).getIfAvailable();
    }
}