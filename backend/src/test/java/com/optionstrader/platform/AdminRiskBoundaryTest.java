package com.optionstrader.platform;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionstrader.tradingai.TradingAiClient;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
class AdminRiskBoundaryTest {
    @Test void serviceTokenCannotResetKillSwitchButSeparateAdministratorCan() throws Exception{
        var json=new ObjectMapper();var paper=mock(PaperPlatformService.class);
        when(paper.kill(false)).thenReturn(json.createObjectNode().put("killSwitch",false));
        var controller=new PlatformController(paper,mock(NormalizedMarketGateway.class),mock(SessionCalendar.class),
            mock(TradingAiClient.class),Clock.systemUTC(),"administrator-secret","internal-secret");
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new PlatformErrors())
            .addFilter(new InternalApiSecurity("internal-secret",json)).build();
        mvc.perform(post("/api/v1/risk/kill-switch").header("Authorization","Bearer internal-secret")
            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andExpect(status().isForbidden());
        verify(paper,never()).kill(false);
        mvc.perform(post("/api/v1/risk/kill-switch").header("Authorization","Bearer internal-secret")
            .header("X-Admin-Token","administrator-secret").contentType(MediaType.APPLICATION_JSON)
            .content("{\"enabled\":false}")).andExpect(status().isOk());
        verify(paper).kill(false);
    }
}
