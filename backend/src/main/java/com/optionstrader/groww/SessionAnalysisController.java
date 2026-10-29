package com.optionstrader.groww;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientResponseException;

@RestController
public class SessionAnalysisController {
    private final SessionAnalysisService sessions;
    public SessionAnalysisController(SessionAnalysisService sessions) {this.sessions=sessions;}

    @PostMapping("/api/backtesting/analyze/session")
    public Map<String,Object> poll(@RequestBody JsonNode body) {return sessions.poll(body);}

    @ExceptionHandler(GrowwReadBudget.LimitExceeded.class)
    public ResponseEntity<Map<String,Object>> budget(GrowwReadBudget.LimitExceeded error) {
        return ResponseEntity.status(429).header("Retry-After",Integer.toString(error.retryAfter()))
            .body(Map.of("error",error.getMessage()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,Object>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error",error.getMessage()));
    }
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<Map<String,Object>> upstream(RestClientResponseException error) {
        var response=ResponseEntity.status(error.getStatusCode().value()==429?429:502);
        if(error.getResponseHeaders()!=null && error.getResponseHeaders().getFirst("Retry-After")!=null)
            response.header("Retry-After",error.getResponseHeaders().getFirst("Retry-After"));
        return response.body(Map.of("error","Groww request failed with HTTP "+error.getStatusCode().value()+"."));
    }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String,Object>> unavailable(RuntimeException error) {
        return ResponseEntity.status(503).body(Map.of("error","Automatic analysis is temporarily unavailable ("+
            error.getClass().getSimpleName()+"). Check the service connection and try again."));
    }
}
