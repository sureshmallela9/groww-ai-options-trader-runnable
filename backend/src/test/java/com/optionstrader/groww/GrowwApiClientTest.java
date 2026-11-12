package com.optionstrader.groww;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GrowwApiClientTest {
    @Test void ampersandStockSymbolIsOneEncodedQueryValue() {
        var builder=RestClient.builder().baseUrl("https://api.groww.in");
        var server=MockRestServiceServer.bindTo(builder).build();
        var auth=mock(GrowwAuthService.class);when(auth.accessToken()).thenReturn("synthetic-test-token");
        server.expect(request->{
            assertThat(request.getURI().getRawQuery()).contains("groww_symbol=NSE-M%26M");
            assertThat(request.getURI().getRawQuery().split("&")).hasSize(2);
        }).andRespond(withSuccess("{\"status\":\"SUCCESS\",\"payload\":{\"candles\":[]}}",MediaType.APPLICATION_JSON));
        var api=new GrowwApiClient(builder.build(),RestClient.create(),auth);
        api.getEnvelope("/v1/historical/candles",Map.of("groww_symbol","NSE-M&M","exchange","NSE"));
        server.verify();
    }
}
