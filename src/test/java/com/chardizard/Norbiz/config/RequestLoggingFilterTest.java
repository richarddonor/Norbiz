package com.chardizard.Norbiz.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter(Tracer.NOOP);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger requestLogger = (Logger) LoggerFactory.getLogger("http.request");

    @BeforeEach
    void attachAppender() {
        appender.start();
        requestLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        requestLogger.detachAppender(appender);
        MDC.clear();
    }

    @Test
    void requestLogLinesCarryUserAndCompanyAndMdcIsClearedAfterwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/items");
        request.addHeader(RequestLoggingFilter.COMPANY_ID_HEADER, "42");
        Map<String, String> mdcInsideChain = new HashMap<>();

        // Stands in for JwtAuthFilter (which runs inside this filter) authenticating the caller.
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                MDC.put(RequestLoggingFilter.MDC_ENDUSER_ID, "jdoe");
                mdcInsideChain.putAll(MDC.getCopyOfContextMap());
            }
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(mdcInsideChain).containsEntry(RequestLoggingFilter.MDC_COMPANY_ID, "42");
        ILoggingEvent outcomeLine = appender.list.getLast();
        assertThat(outcomeLine.getFormattedMessage()).startsWith("<-- GET /items");
        assertThat(outcomeLine.getMDCPropertyMap())
                .containsEntry(RequestLoggingFilter.MDC_ENDUSER_ID, "jdoe")
                .containsEntry(RequestLoggingFilter.MDC_COMPANY_ID, "42");
        // A pooled thread must not carry this request's user into the next one.
        assertThat(MDC.get(RequestLoggingFilter.MDC_ENDUSER_ID)).isNull();
        assertThat(MDC.get(RequestLoggingFilter.MDC_COMPANY_ID)).isNull();
    }

    @Test
    void nonNumericCompanyHeaderIsNotLogged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/items");
        request.addHeader(RequestLoggingFilter.COMPANY_ID_HEADER, "1\nFAKE LOG LINE");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(appender.list).allSatisfy(event ->
                assertThat(event.getMDCPropertyMap()).doesNotContainKey(RequestLoggingFilter.MDC_COMPANY_ID));
    }
}
