package com.optionstrader;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionstrader.tradingai.AnalysisRequestMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ResearchRequestTest {
    final ObjectMapper json=new ObjectMapper();final AnalysisRequestMapper mapper=new AnalysisRequestMapper();
    com.fasterxml.jackson.databind.node.ObjectNode request(){
        var body=json.createObjectNode();body.put("symbol","DEMO");body.put("interval","5m");var rows=body.putArray("bars");
        for(int i=0;i<60;i++){var bar=rows.addObject();bar.put("timestamp",Instant.parse("2026-01-05T03:45:00Z").plusSeconds(i*300L).toString());bar.put("open",100+i);bar.put("high",103+i);bar.put("low",99+i);bar.put("close",101+i);bar.put("volume",1000+i);}
        return body;
    }
    @Test void forwardsResearchSettingsAndRejectsUnexpectedFields(){
        var body=request();body.putObject("analysis_options").put("mode","historical").putArray("context_intervals").add("15m").add("1h");
        var mapped=mapper.map(body);assertThat(json.valueToTree(mapped).at("/analysis_options/context_intervals/1").asText()).isEqualTo("1h");
        body.put("unrecognized",true);assertThatThrownBy(()->mapper.map(body)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void replayHasItsOwnParametersAndRetainsStrictRequestValidation(){
        var body=request();body.putObject("replay_options").put("fee_bps",7).put("holding_bars",3);
        assertThat(json.valueToTree(mapper.mapReplay(body)).at("/replay_options/fee_bps").asInt()).isEqualTo(7);
        assertThatThrownBy(()->mapper.map(body)).isInstanceOf(IllegalArgumentException.class);
        body.put("replay_options","bad");assertThatThrownBy(()->mapper.mapReplay(body)).isInstanceOf(IllegalArgumentException.class);
    }
}
