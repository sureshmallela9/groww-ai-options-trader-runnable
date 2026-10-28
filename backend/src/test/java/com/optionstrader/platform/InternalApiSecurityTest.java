package com.optionstrader.platform;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
class InternalApiSecurityTest {
    @Test void rejectsMissingAndIncorrectTokensAndAllowsCorrectToken() throws Exception {
        var filter=new InternalApiSecurity("shared-secret",new ObjectMapper());
        for(String token:new String[]{"","Bearer wrong","Bearer shared-secret"}){
            var request=new MockHttpServletRequest("POST","/api/v1/trade-intents");
            request.addHeader("Authorization",token);var response=new MockHttpServletResponse();var chain=new MockFilterChain();
            filter.doFilter(request,response,chain);
            if(token.endsWith("shared-secret"))assertThat(chain.getRequest()).isNotNull();
            else assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getHeader("X-Correlation-ID")).isNotBlank();
        }
    }
    @Test void unconfiguredTokenFailsClosed() throws Exception {
        var response=new MockHttpServletResponse();
        new InternalApiSecurity("",new ObjectMapper()).doFilter(
            new MockHttpServletRequest("POST","/api/v1/trade-intents"),response,new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(503);
    }
}
