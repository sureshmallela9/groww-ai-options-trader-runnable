package com.optionstrader.platform;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class SessionCalendarTest {
    @TempDir Path directory;
    @Test void missingCalendarAndMissingDatesBlockEntries() throws Exception {
        var file=directory.resolve("calendar.json");
        var clock=Clock.fixed(Instant.parse("2026-10-28T04:30:00Z"),ZoneOffset.UTC);
        var calendar=new SessionCalendar(file.toString(),new ObjectMapper());
        assertThat(calendar.status(clock)).containsEntry("verified",false).containsEntry("entryAllowed",false);
        Files.writeString(file,"""
            {"verified":true,"sourceUrl":"https://official.example/calendar","sessions":{
                "2026-10-28":{"open":"09:15","close":"15:30","entryCutoff":"14:58","squareOff":"14:57"}}}
            """);
        assertThat(calendar.status(clock)).containsEntry("verified",true).containsEntry("entryAllowed",true);
        assertThat(calendar.status(Clock.offset(clock,Duration.ofDays(1)))).containsEntry("entryAllowed",false);
        assertThat(calendar.status(Clock.offset(clock,Duration.ofHours(5)))).containsEntry("squareOffDue",true).containsEntry("entryAllowed",false);
    }
}
