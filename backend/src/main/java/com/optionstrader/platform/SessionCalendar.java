package com.optionstrader.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.LinkedHashMap;
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
    Path resolvedFile(){
        return resolvedFile(Path.of("").toAbsolutePath().normalize());
    }
    Path resolvedFile(Path cwd){
        Path candidate=(file.isAbsolute()?file:cwd.resolve(file)).toAbsolutePath().normalize();
        if(Files.isRegularFile(candidate)||file.isAbsolute())return candidate;
        if(file.normalize().equals(Path.of("config","session-calendar.json"))&&
           cwd.getFileName()!=null&&cwd.getFileName().toString().equals("backend")&&Files.isRegularFile(cwd.resolve("pom.xml"))){
            Path parent=cwd.getParent().resolve(file).normalize();if(Files.isRegularFile(parent))return parent;
        }
        return candidate;
    }
    private Map<String,Object> blocked(Map<String,Object> value,String code,String reason,boolean verified){
        value.put("verified",verified);value.put("reasonCode",code);value.put("reason",reason);return value;
    }
    public Map<String,Object> status(Clock clock) {
        var now=clock.instant().atZone(ZoneId.of("Asia/Kolkata"));
        Path location=resolvedFile();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("verified",false);result.put("sessionOpen",false);result.put("entryAllowed",false);
        result.put("squareOffDue",false);result.put("date",now.toLocalDate().toString());
        result.put("currentTime",now.toLocalTime().withNano(0).toString());result.put("timeZone","Asia/Kolkata");
        result.put("calendarFile",location.toString());
        if(!Files.isRegularFile(location))return blocked(result,"CALENDAR_MISSING",
            "Session calendar file was not found: "+location+". Configure TRADING_SESSION_CALENDAR with the reviewed file path.",false);
        try {
            JsonNode root=json.readTree(Files.readString(location));
            boolean verified=root.path("verified").asBoolean(false)&&root.path("sourceUrl").asText().startsWith("https://");
            JsonNode session=root.path("sessions").path(now.toLocalDate().toString());
            if(!verified)return blocked(result,"CALENDAR_NOT_REVIEWED","The configured calendar has not been marked reviewed with an HTTPS source.",false);
            result.put("sourceUrl",root.path("sourceUrl").asText());
            var next=new java.util.TreeSet<String>();
            root.path("sessions").fieldNames().forEachRemaining(day->{try{
                if(java.time.LocalDate.parse(day).isAfter(now.toLocalDate()))next.add(day);
            }catch(Exception invalid){/* invalid keys never authorize a session */}});
            result.put("nextEntrySession",next.isEmpty()?"":next.first()+" "+root.path("sessions").path(next.first()).path("open").asText("09:15")+" IST");
            if(!session.isObject()){
                String date=now.toLocalDate().toString();
                if(root.path("specialDates").has(date))return blocked(result,"SPECIAL_SESSION_NOT_CONFIGURED",root.path("specialDates").path(date).asText(),false);
                if(root.path("closedDates").has(date))return blocked(result,"NON_TRADING_DAY",root.path("closedDates").path(date).asText()+". No regular session is configured today.",true);
                return blocked(result,"DATE_NOT_CONFIGURED","The reviewed calendar has no session for "+date+"; update its coverage before enabling entries.",false);
            }
            LocalTime open=LocalTime.parse(session.path("open").asText());
            LocalTime close=LocalTime.parse(session.path("close").asText());
            LocalTime cutoff=LocalTime.parse(session.path("entryCutoff").asText("14:58"));
            LocalTime square=LocalTime.parse(session.path("squareOff").asText("14:57"));
            if(!open.isBefore(close)||cutoff.isBefore(open)||cutoff.isAfter(close)||square.isBefore(open)||square.isAfter(close))
                return blocked(result,"INVALID_SESSION_TIMES","Session open/close, entry cutoff or square-off times are invalid.",false);
            boolean marketOpen=!now.toLocalTime().isBefore(open)&&now.toLocalTime().isBefore(close);
            LocalTime end=cutoff.isBefore(square)?cutoff:square;
            boolean allowed=marketOpen&&now.toLocalTime().isBefore(end);
            result.put("verified",true);result.put("sessionOpen",marketOpen);result.put("entryAllowed",allowed);
            result.put("squareOffDue",!now.toLocalTime().isBefore(square));
            result.put("open",open.toString());result.put("close",close.toString());
            result.put("entryCutoff",cutoff.toString());result.put("squareOff",square.toString());result.put("entryEndsAt",end.toString());
            if(now.toLocalTime().isBefore(open)){
                result.put("nextEntrySession",now.toLocalDate()+" "+open+" IST");
                return blocked(result,"BEFORE_OPEN","Waiting for the configured "+open+" IST opening time.",true);
            }
            if(!now.toLocalTime().isBefore(close))return blocked(result,"MARKET_CLOSED","The regular session closed at "+close+" IST. No new entries are allowed.",true);
            if(!allowed)return blocked(result,"ENTRY_WINDOW_CLOSED","New entries stopped at "+end+" IST under your entry/square-off rules. The market session closes at "+close+" IST.",true);
            result.put("reasonCode","ENTRY_WINDOW_OPEN");result.put("reason","The reviewed entry session is open.");return result;
        } catch(Exception missing) {
            result.put("sessionOpen",false);result.put("entryAllowed",false);result.put("squareOffDue",false);
            return blocked(result,"CALENDAR_INVALID","The calendar could not be read or contains invalid dates/times: "+location+".",false);
        }
    }
}
