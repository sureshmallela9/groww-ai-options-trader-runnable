package com.optionstrader.groww;

import org.springframework.web.bind.annotation.*;

/** Authenticated local report reads; never calls Groww or starts trading. */
@RestController
@RequestMapping("/api/v1/reports/groww-api")
public class GrowwApiAuditController {
    private final GrowwApiAuditStore store;
    public GrowwApiAuditController(GrowwApiAuditStore store){this.store=store;}
    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<?> invalid(IllegalArgumentException error){return org.springframework.http.ResponseEntity.badRequest().body(java.util.Map.of("error",error.getMessage()));}
    @ExceptionHandler(IllegalStateException.class)
    public org.springframework.http.ResponseEntity<?> unavailable(IllegalStateException error){return org.springframework.http.ResponseEntity.status(503).body(java.util.Map.of("error",error.getMessage()));}
    @GetMapping public Object report(@RequestParam String from,@RequestParam String to,
        @RequestParam(defaultValue="ALL")String category,@RequestParam(defaultValue="ALL")String outcome,
        @RequestParam(defaultValue="")String symbol,@RequestParam(defaultValue="200")int limit,
        @RequestParam(required=false)String cursor,@RequestParam(required=false)String snapshotAt){
        return store.report(store.query(from,to,category,outcome,symbol,limit,cursor,snapshotAt));
    }
    @GetMapping("/export") public Object export(@RequestParam String from,@RequestParam String to,
        @RequestParam(defaultValue="ALL")String category,@RequestParam(defaultValue="ALL")String outcome,
        @RequestParam(defaultValue="")String symbol,@RequestParam(required=false)String snapshotAt){
        return store.export(store.query(from,to,category,outcome,symbol,500,null,snapshotAt));
    }
}
