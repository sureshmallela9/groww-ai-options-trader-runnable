package com.optionstrader.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class InternalApiSecurity extends OncePerRequestFilter {
    private final String token;
    private final ObjectMapper json;
    public InternalApiSecurity(@Value("${trading.internal-token:}") String token,ObjectMapper json) {
        this.token=token;this.json=json;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        String correlation=request.getHeader("X-Correlation-ID");
        if(correlation==null||!correlation.matches("[A-Za-z0-9_-]{1,64}"))correlation=UUID.randomUUID().toString();
        response.setHeader("X-Correlation-ID",correlation);
        request.setAttribute("correlationId",correlation);
        if(request.getRequestURI().equals("/api/v1/health")){chain.doFilter(request,response);return;}
        String supplied=request.getHeader("Authorization");
        if(token.isBlank()||supplied==null||!MessageDigest.isEqual(
                ("Bearer "+token).getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(token.isBlank()?503:401);response.setContentType("application/json");
            json.writeValue(response.getOutputStream(),Map.of("error",token.isBlank()?"Internal API token is not configured.":"Internal API authorization is required.",
                "correlationId",correlation));return;
        }
        chain.doFilter(request,response);
    }
}
