package com.optionstrader.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Explicit session dates prevent weekday/holiday assumptions. */
@Component
public class SessionCalendar {
    private final Path file;
    private final ObjectMapper json;
    public SessionCalendar(@Value("${trading.session.calendar-file:config/session-calendar.json}") String file,ObjectMapper json){
        this.file=Path.of(file);this.json=json;
    }
    public Map<String,Object> status(Clock clock) {
        var now=clock.instant().atZone(ZoneId.of("Asia/Kolkata"));
        try {
            JsonNode root=json.readTree(Files.readString(file));
            boolean verified=root.path("verified").asBoolean(false)&&root.path("sourceUrl").asText().startsWith("https://");
            JsonNode session=root.path("sessions").path(now.toLocalDate().toString());
            if(!verified||!session.isObject())throw new IllegalStateException();
            LocalTime open=LocalTime.parse(session.path("open").asText());
            LocalTime close=LocalTime.parse(session.path("close").asText());
            LocalTime cutoff=LocalTime.parse(session.path("entryCutoff").asText("14:58"));
            LocalTime square=LocalTime.parse(session.path("squareOff").asText("14:57"));
            if(!open.isBefore(close)||cutoff.isAfter(close)||square.isAfter(close))throw new IllegalStateException();
            boolean marketOpen=!now.toLocalTime().isBefore(open)&&now.toLocalTime().isBefore(close);
            return Map.of("verified",true,"sessionOpen",marketOpen,
                "entryAllowed",marketOpen&&now.toLocalTime().isBefore(cutoff)&&now.toLocalTime().isBefore(square),
                "squareOffDue",!now.toLocalTime().isBefore(square),"date",now.toLocalDate().toString(),
                "sourceUrl",root.path("sourceUrl").asText());
        } catch(Exception missing) {
            return Map.of("verified",false,"sessionOpen",false,"entryAllowed",false,
                          "squareOffDue",false,"date",now.toLocalDate().toString(),
                          "reason","No verified session is configured for this date.");
        }
    }
}
