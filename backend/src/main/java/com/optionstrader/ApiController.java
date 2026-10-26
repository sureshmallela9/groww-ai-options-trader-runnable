package com.optionstrader;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ApiController {
 private final TradingService trading; private final AnalysisService analysis;
 public ApiController(TradingService trading,AnalysisService analysis){this.trading=trading;this.analysis=analysis;}
 @GetMapping("/health") public Map<String,Object> health(){return Map.of("status","UP","mode","PAPER","liveExecutionEnabled",false);}
 @GetMapping("/dashboard") public Map<String,Object> dashboard(){return trading.dashboard();}
 @GetMapping("/settings") public Map<String,Object> settings(){return trading.settings();}
 @PutMapping("/settings") public Map<String,Object> update(@RequestBody Map<String,Object> body){return trading.update(body);}
 @PostMapping("/analyze") public Map<String,Object> analyze(){return analysis.analyze();}
 @GetMapping("/ai/status") public Map<String,Object> aiStatus(){return analysis.status();}
 @GetMapping("/trades") public List<Map<String,Object>> trades(){return trading.all();}
 @GetMapping("/trades/{id}") public Map<String,Object> trade(@PathVariable String id){return trading.get(id);}
 @PostMapping("/paper/execute") public Map<String,Object> execute(){return trading.execute();}
 @PostMapping("/paper/close/{id}") public Map<String,Object> close(@PathVariable String id){return trading.close(id);}
 @PostMapping("/paper/square-off") public Map<String,Object> squareOff(){return Map.of("closed",trading.squareOff());}
 @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST) public Map<String,String> bad(IllegalArgumentException e){return Map.of("error",e.getMessage());}
 @ExceptionHandler(IllegalStateException.class) @ResponseStatus(HttpStatus.CONFLICT) public Map<String,String> conflict(IllegalStateException e){return Map.of("error",e.getMessage());}
 @ExceptionHandler(NoSuchElementException.class) @ResponseStatus(HttpStatus.NOT_FOUND) public Map<String,String> missing(NoSuchElementException e){return Map.of("error",e.getMessage());}
}
