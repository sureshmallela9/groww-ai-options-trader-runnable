package com.optionstrader.platform;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;
import com.optionstrader.groww.GrowwReadBudget;
@RestControllerAdvice(assignableTypes={PlatformController.class,AutoTradingController.class})
public class PlatformErrors {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(PlatformErrors.class);
    @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,Object>> error(Exception error,HttpServletRequest request){
        int status=error instanceof GrowwReadBudget.LimitExceeded?429:error instanceof ResponseStatusException response?response.getStatusCode().value():
            error instanceof IllegalArgumentException?422:error instanceof ResourceAccessException?503:
            error instanceof RestClientResponseException?502:500;
        if(status>=500){
            // Stack locations and exception classes aid diagnosis without logging credentials, request bodies or SQL payloads.
            LOG.error("Platform {} {} failed; correlation={}; exception={}; locations={}",request.getMethod(),request.getRequestURI(),
                request.getAttribute("correlationId"),error.getClass().getName(),java.util.Arrays.toString(error.getStackTrace()));
            if(error.getCause()!=null)LOG.error("Platform root cause class={}",error.getCause().getClass().getName());
            if(error.getCause() instanceof java.sql.SQLException sql)LOG.error("Database state={}, vendorCode={}",sql.getSQLState(),sql.getErrorCode());
        }
        String message=error instanceof ResponseStatusException response?response.getReason():
            error instanceof IllegalArgumentException||error instanceof GrowwReadBudget.LimitExceeded?error.getMessage():
            status==503?"A required service is unavailable.":status==502?"An upstream API rejected the request.":"The platform request failed; check service diagnostics.";
        var body=new LinkedHashMap<String,Object>();body.put("error",message);body.put("correlationId",request.getAttribute("correlationId"));
        var result=ResponseEntity.status(status);
        if(error instanceof GrowwReadBudget.LimitExceeded limit)result.header("Retry-After",Integer.toString(limit.retryAfter()));
        return result.body(body);
    }
}
