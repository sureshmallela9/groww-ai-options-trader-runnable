package com.optionstrader.platform;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:platform-wiring;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS GROWW_TRADER",
    "spring.datasource.hikari.connection-init-sql=SET SCHEMA GROWW_TRADER",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=none","spring.jpa.properties.hibernate.default_schema=GROWW_TRADER",
    "spring.flyway.locations=classpath:db/postgresql","spring.flyway.schemas=GROWW_TRADER","spring.flyway.default-schema=GROWW_TRADER",
    "groww.api.enabled=false","trading.ai.enabled=false","trading.platform.monitor-enabled=false",
    "trading.internal-token=full-context-test-token","trading.admin-token=separate-test-admin-token"
})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PlatformStartupTest {
    @Test void automaticControlsRequireAuthenticationAndRefuseLiveOrders(){
        assertThatThrownBy(()->client(false).get().uri("/api/v1/auto/status").retrieve().body(String.class))
            .isInstanceOfSatisfying(RestClientResponseException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(401));
        JsonNode status=client(true).get().uri("/api/v1/auto/status").retrieve().body(JsonNode.class);
        assertThat(status.at("/automation/enabled").asBoolean()).isFalse();
        assertThatThrownBy(()->client(true).post().uri("/api/v1/auto/start").contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("mode","LIVE","confirmed",true)).retrieve().body(String.class))
            .isInstanceOfSatisfying(RestClientResponseException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(409));
        String page=client(false).get().uri("/").retrieve().body(String.class);
        assertThat(page).contains("automatic-paper.js","id=\"automatic-paper\"");
    }
    @LocalServerPort int port;
    RestClient client(boolean authenticated){
        var builder=RestClient.builder().baseUrl("http://127.0.0.1:"+port);
        if(authenticated)builder.defaultHeader("Authorization","Bearer full-context-test-token");
        return builder.build();
    }
    @Test void fullApplicationServesTheWorkspaceAndProtectsRiskState(){
        String page=client(false).get().uri("/paper-workspace.html").retrieve().body(String.class);
        assertThat(page).contains("Paper research workspace","paper-workspace.js");
        assertThatThrownBy(()->client(false).get().uri("/api/v1/risk/status").retrieve().body(String.class))
            .isInstanceOfSatisfying(RestClientResponseException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(401));
        JsonNode status=client(true).get().uri("/api/v1/risk/status").retrieve().body(JsonNode.class);
        assertThat(status.path("liveExecutionEnabled").asBoolean()).isFalse();
        assertThat(status.path("autoEntryEnabled").asBoolean()).isFalse();
        assertThat(status.at("/policy/capital").decimalValue()).isEqualByComparingTo("10000");
    }
    @Test void jpaTransactionManagerCommitsTheIndependentKillSwitch(){
        client(true).post().uri("/api/v1/risk/kill-switch").contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("enabled",true)).retrieve().toBodilessEntity();
        JsonNode status=client(true).get().uri("/api/v1/risk/status").retrieve().body(JsonNode.class);
        assertThat(status.path("killSwitch").asBoolean()).isTrue();
    }
}
