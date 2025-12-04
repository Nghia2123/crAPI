package com.crapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class ExtendedLoggingFilter extends OncePerRequestFilter {

    private static final String LOG_DIR = System.getenv("EXTENDED_LOG_DIR") != null 
        ? System.getenv("EXTENDED_LOG_DIR") : "/app/logs";
    private static final String LOG_FILE = LOG_DIR + "/http_extended.jsonl";
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ExtendedLoggingFilter() {
        new File(LOG_DIR).mkdirs();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        ContentCachingRequestWrapper wrappedRequest = 
            new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper wrappedResponse = 
            new ContentCachingResponseWrapper(response);

        long startTime = System.currentTimeMillis();

        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            logExtendedEntry(wrappedRequest, wrappedResponse, startTime);
            wrappedResponse.copyBodyToResponse();
        }
    }

    private void logExtendedEntry(
            ContentCachingRequestWrapper request,
            ContentCachingResponseWrapper response,
            long startTime) {

        try {
            Map<String, Object> logEntry = new HashMap<>();

            logEntry.put("timestamp", Instant.now().toString());

            logEntry.put("ip", getClientIp(request));

            logEntry.put("method", request.getMethod());

            String uri = request.getRequestURI();
            String queryString = request.getQueryString();
            if (queryString != null) {
                uri += "?" + queryString;
            }
            logEntry.put("uri", uri);

            String requestBody = new String(request.getContentAsByteArray(),
                request.getCharacterEncoding() != null ?
                request.getCharacterEncoding() : "UTF-8");
            logEntry.put("requestBody", parseJsonOrString(requestBody));

            String responseBody = new String(response.getContentAsByteArray(),
                response.getCharacterEncoding() != null ?
                response.getCharacterEncoding() : "UTF-8");
            logEntry.put("responseBody", parseJsonOrString(responseBody));

            logEntry.put("statusCode", response.getStatus());

            String authHeader = request.getHeader("Authorization");
            logEntry.put("headerAuthorization", authHeader != null ? authHeader : "");

            logEntry.put("duration_ms", System.currentTimeMillis() - startTime);

            writeLogToFile(logEntry);

        } catch (Exception e) {
            log.error("Error logging extended entry", e);
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private Object parseJsonOrString(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        try {
            return objectMapper.readValue(content, Object.class);
        } catch (Exception e) {
            return content;
        }
    }

    private synchronized void writeLogToFile(Map<String, Object> logEntry) {
        try (PrintWriter writer = new PrintWriter(
                new FileWriter(LOG_FILE, true))) {
            writer.println(objectMapper.writeValueAsString(logEntry));
        } catch (IOException e) {
            log.error("Failed to write log entry", e);
        }
    }
}
