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
    "groww.api.enabled=false","groww.api.poll-interval=2500","trading.ai.enabled=false","trading.platform.monitor-enabled=false",
    "trading.internal-token=full-context-test-token","trading.admin-token=separate-test-admin-token",
    "trading.groww-audit.directory=target/test-groww-audit"
})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PlatformStartupTest {
    @Test void growwAuditHasItsOwnRouteAndAuthenticatedReadOnlyReport(){
        String path="/api/v1/reports/groww-api?from=2026-01-01&to=2026-01-01";
        assertThatThrownBy(()->client(false).get().uri(path).retrieve().body(String.class)).isInstanceOfSatisfying(RestClientResponseException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(401));
        assertThatThrownBy(()->client(false).get().uri(path.replace("groww-api?","groww-api/export?")).retrieve().body(String.class)).isInstanceOfSatisfying(RestClientResponseException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(401));
        JsonNode report=client(true).get().uri(path).retrieve().body(JsonNode.class);
        assertThat(report.path("records").isArray()).isTrue();assertThat(report.at("/coverage/scope").asText()).isEqualTo("OUTGOING_GROWW_HTTP_ATTEMPTS");
        assertThat(client(true).get().uri(path.replace("groww-api?","groww-api/export?")).retrieve().body(JsonNode.class).path("csv").asText()).contains("Started UTC");
        assertThat(client(false).get().uri("/workspace/audit").retrieve().body(String.class)).contains("groww-api-audit.js","audit-filters","Export filtered CSV");
        assertThatThrownBy(()->client(true).get().uri("/api/v1/reports/groww-api?from=bad&to=2026-01-01").retrieve().body(String.class)).isInstanceOfSatisfying(RestClientResponseException.class,e->assertThat(e.getStatusCode().is4xxClientError()).isTrue());
    }
    @Test void configuredPaperProfileLoadsAndEditableRulesPersistThroughTheAuthenticatedApi(){
        JsonNode status=client(true).get().uri("/api/v1/risk/status").retrieve().body(JsonNode.class);
        assertThat(status.at("/intraday/fallbackEnabled").asBoolean()).isTrue();
        assertThat(status.at("/intraday/paperLeverage").asInt()).isEqualTo(5);
        assertThat(status.at("/intraday/priceBands").size()).isEqualTo(4);
        client(true).put().uri("/api/v1/risk/intraday").header("X-Admin-Token","separate-test-admin-token")
            .contentType(MediaType.APPLICATION_JSON).body(IntradayRules.paperDefaults()).retrieve().toBodilessEntity();
        status=client(true).get().uri("/api/v1/risk/status").retrieve().body(JsonNode.class);
        assertThat(status.at("/intraday/fallbackMaxPrice").asInt()).isEqualTo(500);
        assertThat(status.path("liveExecutionEnabled").asBoolean()).isFalse();
        String page=client(false).get().uri("/workspace/risk").retrieve().body(String.class);
        assertThat(page).contains("intraday-rules-form","intraday-rules.js","Save intraday rules");
    }
    @Test void automaticControlsRequireAuthenticationAndRefuseLiveOrders(){
        assertThatThrownBy(()->client(false).get().uri("/api/v1/auto/status").retrieve().body(String.class))
            .isInstanceOfSatisfying(RestClientResponseException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(401));
        JsonNode status=client(true).get().uri("/api/v1/auto/status").retrieve().body(JsonNode.class);
        assertThat(status.at("/automation/enabled").asBoolean()).isFalse();
        assertThat(status.at("/automation/activity/status").asText()).isEqualTo("STOPPED");
        assertThat(status.at("/automation/activity/processing").asBoolean()).isFalse();
        assertThat(status.at("/automation/activity/serverTime").asText()).isNotBlank();
        assertThat(status.at("/automation/activity/checkIntervalMs").asInt()).isEqualTo(5000);
        assertThat(status.at("/automation/flow/growwIndexPollMs").asInt()).isEqualTo(2500);
        assertThat(status.at("/automation/flow/scanCandleMs").asInt()).isEqualTo(300000);
        assertThat(status.at("/automation/flow/growwEnabled").asBoolean()).isFalse();
        assertThat(status.at("/automation/flow/positionMonitorEnabled").asBoolean()).isFalse();
        assertThatThrownBy(()->client(true).post().uri("/api/v1/auto/start").contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("mode","LIVE","confirmed",true)).retrieve().body(String.class))
            .isInstanceOfSatisfying(RestClientResponseException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(409));
        String page=client(false).get().uri("/").retrieve().body(String.class);
        assertThat(page).contains("automatic-paper.js","id=\"automatic-paper\"", "automation-activity.js", "workspace-automation-progress", "system-flow.js", "data-open-system-flow");
        assertThat(client(false).get().uri("/automation-activity.js").retrieve().body(String.class))
            .contains("root.AutomationActivity", "Last server check");
        assertThat(client(false).get().uri("/system-flow.html").retrieve().body(String.class))
            .contains("data-system-flow", "system-flow.js", "System flow");
        assertThat(client(false).get().uri("/system-flow.js").retrieve().body(String.class))
            .contains("TRADING_FLOW_UPDATE", "postMessage");
    }
    @Test void profitLossEndpointUsesSavedLedgerAndRequiresAuthentication(){
        String query="/api/v1/reports/profit-loss?from=2026-01-01&to=2026-01-10&groupBy=DAY&mode=PAPER";
        assertThatThrownBy(()->client(false).get().uri(query).retrieve().body(String.class)).isInstanceOfSatisfying(RestClientResponseException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(401));
        JsonNode report=client(true).get().uri(query).retrieve().body(JsonNode.class);
        assertThat(report.path("mode").asText()).isEqualTo("PAPER");assertThat(report.path("groups").size()).isEqualTo(10);assertThat(report.path("scope").asText()).isEqualTo("SAVED_MANAGED_TRADES_ONLY");
        assertThatThrownBy(()->client(true).get().uri("/api/v1/reports/profit-loss?from=bad&to=2026-01-01").retrieve().body(String.class)).isInstanceOfSatisfying(RestClientResponseException.class,e->assertThat(e.getStatusCode().is4xxClientError()).isTrue());
    }
    @LocalServerPort int port;
    RestClient client(boolean authenticated){
        var builder=RestClient.builder().baseUrl("http://127.0.0.1:"+port);
        if(authenticated)builder.defaultHeader("Authorization","Bearer full-context-test-token");
        return builder.build();
    }
    @Test void fullApplicationServesTheWorkspaceAndProtectsRiskState(){
        String page=client(false).get().uri("/paper-workspace.html").retrieve().body(String.class);
        assertThat(page).contains("Trading Workspace","workspace-shell.js","Paper trading","Real trading");
        assertThat(client(false).get().uri("/research-tools.html").retrieve().body(String.class))
            .contains("workspace-research.js","id=\"research-sector\"");
        assertThatThrownBy(()->client(false).get().uri("/api/v1/risk/status").retrieve().body(String.class))
            .isInstanceOfSatisfying(RestClientResponseException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(401));
        JsonNode status=client(true).get().uri("/api/v1/risk/status").retrieve().body(JsonNode.class);
        assertThat(status.path("liveExecutionEnabled").asBoolean()).isFalse();
        assertThat(status.path("autoEntryEnabled").asBoolean()).isFalse();
        assertThat(status.at("/policy/capital").decimalValue()).isEqualByComparingTo("10000");
    }
    @Test void uiRoutesAndReloadsServeOnlyTheWorkspaceShell(){
        for(String path:new String[]{"/workspace", "/workspace/", "/workspace/overview", "/workspace/automation",
                "/workspace/news", "/workspace/analysis", "/workspace/reports", "/workspace/reports/123", "/workspace/positions",
                "/workspace/positions/PAPER-example_1", "/workspace/risk", "/workspace/tools"}) {
            assertThat(client(false).get().uri(path).retrieve().body(String.class))
                .as(path).contains("workspace-router.js", "workspace-api.js", "workspace-shell.js");
        }
        for(String path:new String[]{"/workspace/missing", "/workspace/reports/not-a-number", "/api/v1/not-a-route", "/missing-module.js"}) {
            assertThatThrownBy(()->client(true).get().uri(path).retrieve().body(String.class))
                .as(path).isInstanceOfSatisfying(RestClientResponseException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(404));
        }
    }
    @Test void jpaTransactionManagerCommitsTheIndependentKillSwitch(){
        client(true).post().uri("/api/v1/risk/kill-switch").contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("enabled",true)).retrieve().toBodilessEntity();
        JsonNode status=client(true).get().uri("/api/v1/risk/status").retrieve().body(JsonNode.class);
        assertThat(status.path("killSwitch").asBoolean()).isTrue();
    }
}
