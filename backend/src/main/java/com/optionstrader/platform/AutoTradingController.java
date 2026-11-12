package com.optionstrader.platform;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auto")
public class AutoTradingController {
    private final AutoTradingService auto;
    private final PaperPlatformService paper;
    @org.springframework.beans.factory.annotation.Autowired private ExecutionModeService modes;
    public AutoTradingController(AutoTradingService auto,PaperPlatformService paper){this.auto=auto;this.paper=paper;}
    @GetMapping("/status") public Object status(){return Map.of("automation",auto.status(),"portfolio",paper.portfolio(),"risk",paper.status());}
    @PostMapping("/start") public Object start(@RequestBody JsonNode body){return auto.start(body);}
    @PostMapping("/stop") public Object stop(){return auto.stop();}
    @PostMapping("/event-risk") public Object eventRisk(@RequestBody JsonNode body){
        if(!body.path("enabled").isBoolean())throw new IllegalArgumentException("enabled must be boolean.");
        return auto.eventRisk(body.path("enabled").asBoolean());
    }
    @PutMapping("/mode") public Object mode(@RequestBody JsonNode body){auto.stop();var selected=modes.select(body);auto.modeChanged();return selected;}
    @GetMapping("/shortlist") public Object shortlist(){return auto.shortlist();}
    @GetMapping("/chart") public Object chart(@RequestParam String symbol){return auto.chart(symbol);}
}
