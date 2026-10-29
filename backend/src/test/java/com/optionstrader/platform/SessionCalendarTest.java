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
    private Path reviewed() throws Exception {
        Path file=directory.resolve("reviewed.json");
        Files.writeString(file,"""
            {"verified":true,"sourceUrl":"https://official.example/calendar","sessions":{
             "2026-10-28":{"open":"09:15","close":"15:30","entryCutoff":"14:58","squareOff":"14:57"},
             "2026-10-29":{"open":"09:15","close":"15:30","entryCutoff":"14:58","squareOff":"14:57"}},
             "closedDates":{"2026-10-31":"Weekend"},"specialDates":{"2026-11-08":"Special session not configured"}}
            """);return file;
    }
    @Test void missingAndInvalidFilesExplainTheirResolvedLocation() throws Exception {
        Path file=directory.resolve("missing.json");var clock=Clock.fixed(Instant.parse("2026-10-28T04:30:00Z"),ZoneOffset.UTC);
        var calendar=new SessionCalendar(file.toString(),new ObjectMapper());
        assertThat(calendar.status(clock)).containsEntry("reasonCode","CALENDAR_MISSING").containsEntry("entryAllowed",false)
            .containsEntry("calendarFile",file.toAbsolutePath().toString());
        Files.writeString(file,"broken JSON");
        assertThat(calendar.status(clock)).containsEntry("reasonCode","CALENDAR_INVALID").containsEntry("entryAllowed",false);
    }
    @Test void openMarketAfterConfiguredSquareOffDoesNotPermitNewEntries() throws Exception {
        var calendar=new SessionCalendar(reviewed().toString(),new ObjectMapper());
        var morning=Clock.fixed(Instant.parse("2026-10-28T04:30:00Z"),ZoneOffset.UTC);
        assertThat(calendar.status(morning)).containsEntry("reasonCode","ENTRY_WINDOW_OPEN").containsEntry("entryAllowed",true);
        var after=Clock.fixed(Instant.parse("2026-10-28T09:53:00Z"),ZoneOffset.UTC);
        assertThat(calendar.status(after)).containsEntry("verified",true).containsEntry("sessionOpen",true)
            .containsEntry("entryAllowed",false).containsEntry("reasonCode","ENTRY_WINDOW_CLOSED")
            .containsEntry("entryEndsAt","14:57").containsEntry("nextEntrySession","2026-10-29 09:15 IST");
        var closed=Clock.fixed(Instant.parse("2026-10-28T10:01:00Z"),ZoneOffset.UTC);
        assertThat(calendar.status(closed)).containsEntry("reasonCode","MARKET_CLOSED").containsEntry("entryAllowed",false);
    }
    @Test void holidaysSpecialSessionsAndMissingDatesCannotOpenEntries() throws Exception {
        var calendar=new SessionCalendar(reviewed().toString(),new ObjectMapper());
        assertThat(calendar.status(Clock.fixed(Instant.parse("2026-10-31T04:30:00Z"),ZoneOffset.UTC)))
            .containsEntry("reasonCode","NON_TRADING_DAY").containsEntry("entryAllowed",false);
        assertThat(calendar.status(Clock.fixed(Instant.parse("2026-11-08T04:30:00Z"),ZoneOffset.UTC)))
            .containsEntry("reasonCode","SPECIAL_SESSION_NOT_CONFIGURED").containsEntry("entryAllowed",false);
        assertThat(calendar.status(Clock.fixed(Instant.parse("2027-01-01T04:30:00Z"),ZoneOffset.UTC)))
            .containsEntry("reasonCode","DATE_NOT_CONFIGURED").containsEntry("verified",false);
    }
    @Test void defaultCalendarResolvesFromBackendWithoutChangingExplicitPaths() throws Exception {
        Path project=directory.resolve("project"),backend=project.resolve("backend"),config=project.resolve("config/session-calendar.json");
        Files.createDirectories(backend);Files.createDirectories(config.getParent());Files.writeString(backend.resolve("pom.xml"),"<project/>");Files.writeString(config,"{}");
        assertThat(new SessionCalendar("config/session-calendar.json",new ObjectMapper()).resolvedFile(backend)).isEqualTo(config);
        Path explicit=directory.resolve("custom.json");
        assertThat(new SessionCalendar(explicit.toString(),new ObjectMapper()).resolvedFile(backend)).isEqualTo(explicit);
    }
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
