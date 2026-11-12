package com.optionstrader.platform;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.optionstrader.tradingai.TradingAiClient;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NewsReportControllerTest {
    final TradingAiClient ai=mock(TradingAiClient.class);
    final PaperPlatformService paper=mock(PaperPlatformService.class);
    final NormalizedMarketGateway market=mock(NormalizedMarketGateway.class);
    final PlatformController controller=new PlatformController(paper,market,mock(SessionCalendar.class),ai,Clock.systemUTC(),"admin-test","internal-test");
    @Test void dailyReportReadsAreForwardedWithoutTradingOrMarketCalls(){
        when(ai.internalGet("/api/v1/news/daily-reports")).thenReturn(Map.of("reports",java.util.List.of()));
        when(ai.internalGet("/api/v1/news/daily-reports/2026-10-30")).thenReturn(Map.of("reportDate","2026-10-30"));
        assertThat(controller.dailyNews()).isNotNull();assertThat(controller.dailyNews("2026-10-30")).isNotNull();
        verifyNoInteractions(paper,market);
    }
    @Test void invalidReportDatesNeverReachTheAiOrOrderServices(){
        assertThatThrownBy(()->controller.dailyNews("../../orders")).isInstanceOf(java.time.format.DateTimeParseException.class);
        verifyNoInteractions(ai,paper,market);
    }
}
