package dev.fleet.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Capture servlet receipt before body decoding, quota checks, or Kafka acknowledgement. */
@Component
public class IngestTimingFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = "fleet.ingestedAt";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getMethod().equals("POST")
                && request.getRequestURI().equals(request.getContextPath() + "/ingest")) {
            request.setAttribute(ATTRIBUTE, System.currentTimeMillis());
        }
        chain.doFilter(request, response);
    }
}
