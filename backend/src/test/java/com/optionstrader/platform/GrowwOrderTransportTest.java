package com.optionstrader.platform;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.optionstrader.groww.*;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GrowwOrderTransportTest {
    MockRestServiceServer server;GrowwApiClient client;
    @BeforeEach void setup(){var builder=RestClient.builder().baseUrl("https://broker.invalid");server=MockRestServiceServer.bindTo(builder).build();var rest=builder.build();var auth=mock(GrowwAuthService.class);when(auth.accessToken()).thenReturn("synthetic-test-token");client=new GrowwApiClient(rest,rest,auth);}
    void expect(){server.expect(requestTo("https://broker.invalid/v1/order/create")).andExpect(method(HttpMethod.POST)).andExpect(header("X-API-VERSION","1.0")).andExpect(header("Authorization","Bearer synthetic-test-token"));}
    @Test void cashMisMarginUsesDocumentedQueryAndOnlyMarginFields()throws Exception{
        server.expect(requestTo("https://broker.invalid/v1/margins/detail/orders?segment=CASH")).andExpect(method(HttpMethod.POST))
            .andExpect(content().json("[{\"trading_symbol\":\"TEST\",\"transaction_type\":\"SELL\",\"quantity\":3,\"price\":100,\"order_type\":\"LIMIT\",\"product\":\"MIS\",\"exchange\":\"NSE\"}]"))
            .andRespond(withSuccess("{\"status\":\"SUCCESS\",\"payload\":{\"total_requirement\":100}}",MediaType.APPLICATION_JSON));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var adapter=new GrowwOrderAdapter(client,json);
        var request=json.readTree("{\"trading_symbol\":\"TEST\",\"transaction_type\":\"SELL\",\"quantity\":3,\"price\":100,\"order_type\":\"LIMIT\",\"product\":\"MIS\",\"exchange\":\"NSE\",\"segment\":\"CASH\",\"validity\":\"DAY\",\"trigger_price\":0,\"order_reference_id\":\"unused\"}");
        assertThat(adapter.margin(request).path("total_requirement").asInt()).isEqualTo(100);server.verify();
    }
    @Test void orderAcknowledgementDoesNotBecomeAnExecution(){
        server.expect(requestTo("https://broker.invalid/v1/order/create")).andExpect(method(HttpMethod.POST)).andRespond(withSuccess("{\"status\":\"SUCCESS\",\"payload\":{\"groww_order_id\":\"ORDER1\",\"order_status\":\"OPEN\"}}",MediaType.APPLICATION_JSON));
        var response=client.post("/v1/order/create",Map.of("order_reference_id","GT12345678"));assertThat(response.path("groww_order_id").asText()).isEqualTo("ORDER1");assertThat(response.has("filled_quantity")).isFalse();server.verify();
    }
    @Test void rejectedAuthenticationIsDefinitiveAndCanBeReportedWithoutAFalsePendingFill(){
        server.expect(requestTo("https://broker.invalid/v1/order/create")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(()->client.post("/v1/order/create",Map.of())).isInstanceOf(GrowwApiClient.BrokerRejection.class);server.verify();
    }
    @Test void serverFailureRemainsAnUnknownOutcomeAndIsNeverAutomaticallyRetried(){
        server.expect(requestTo("https://broker.invalid/v1/order/create")).andRespond(withServerError());
        assertThatThrownBy(()->client.post("/v1/order/create",Map.of())).isNotInstanceOf(GrowwApiClient.BrokerRejection.class);server.verify();
    }
    @Test void duplicateReferenceMustBeReconciledInsteadOfBeingDeclaredAnUnfilledRejection(){
        server.expect(requestTo("https://broker.invalid/v1/order/create")).andRespond(withSuccess("{\"status\":\"FAILURE\",\"error\":{\"code\":\"GA007\"}}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->client.post("/v1/order/create",Map.of())).isInstanceOf(IllegalStateException.class).isNotInstanceOf(GrowwApiClient.BrokerRejection.class);server.verify();
    }
}
