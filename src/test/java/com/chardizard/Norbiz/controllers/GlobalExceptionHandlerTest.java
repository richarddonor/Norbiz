package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppErrorResponse;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(Tracer.NOOP);
    private final ExceptionHandlerMethodResolver resolver = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

    @Test
    void userLookupFailureDuringLoginResolvesTo503NotBadCredentials() {
        // What DaoAuthenticationProvider throws when the user query fails (e.g. the DB is down).
        InternalAuthenticationServiceException ex =
                new InternalAuthenticationServiceException("Unable to acquire JDBC Connection", new RuntimeException());

        Method method = resolver.resolveMethodByThrowable(ex);
        assertThat(method).isNotNull();
        assertThat(method.getName()).isEqualTo("handleInternalAuthentication");

        ResponseEntity<AppErrorResponse> response = handler.handleInternalAuthentication(ex, new MockHttpServletRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().getMessage()).doesNotContain("password");
    }

    @Test
    void wrongPasswordStillResolvesTo401() {
        Method method = resolver.resolveMethodByThrowable(new BadCredentialsException("Bad credentials"));
        assertThat(method).isNotNull();
        assertThat(method.getName()).isEqualTo("handleAuthentication");
    }
}
