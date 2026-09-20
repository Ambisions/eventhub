package com.eventhub.security;

import java.io.IOException;

import com.eventhub.handler.ApiErrorResponse;
import com.eventhub.handler.ApiErrorWriter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public final class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ApiErrorWriter errorWriter;

    public RestAccessDeniedHandler(ApiErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException, ServletException {
        errorWriter.write(
                response,
                HttpServletResponse.SC_FORBIDDEN,
                new ApiErrorResponse("FORBIDDEN", "The authenticated user is not allowed to access this resource"));
    }
}
