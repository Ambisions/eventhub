package com.eventhub.security;

import java.io.IOException;

import com.eventhub.handler.ApiErrorResponse;
import com.eventhub.handler.ApiErrorWriter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
public final class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiErrorWriter errorWriter;

    public RestAuthenticationEntryPoint(ApiErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException) throws IOException, ServletException {
        errorWriter.write(
                response,
                HttpServletResponse.SC_UNAUTHORIZED,
                new ApiErrorResponse(
                        "UNAUTHORIZED",
                        "Authentication is required or the access token is invalid"));
    }
}
