package com.optionstrader.platform;
import static org.assertj.core.api.Assertions.*;
import com.optionstrader.persistence.PaperTradeEntity;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
/** Exercises Flyway SQL and the legacy JSON string mapping in PostgreSQL-mode H2. */
class PostgresSchemaCompatibilityTest {
    @Test void postgresMigrationPluginIsPresent(){
        assertThat(new org.flywaydb.database.postgresql.PostgreSQLDatabaseType().getName()).isEqualTo("PostgreSQL");
    }
    @Test void additiveOracleSchemaRemainsCompatibleWithTheLegacyTextMapping(){
        String url="jdbc:h2:mem:"+UUID.randomUUID()+";MODE=Oracle;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS GROWW_TRADER\\;SET SCHEMA GROWW_TRADER";
        var source=new DriverManagerDataSource(url,"sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__create_paper_trading_tables.sql"),
            new ClassPathResource("db/migration/V2__paper_platform.sql")).execute(source);
        verifyTextRoundTrip(url);
    }
    @Test void legacyJournalJsonIsStoredAsTextThroughThePostgresProfileSchema(){
        String url="jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS GROWW_TRADER\\;SET SCHEMA GROWW_TRADER";
        var source=new DriverManagerDataSource(url,"sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/postgresql/V1__create_paper_trading_tables.sql"),
            new ClassPathResource("db/postgresql/V2__paper_platform.sql")).execute(source);
        verifyTextRoundTrip(url);
    }
    private void verifyTextRoundTrip(String url){
        var configuration=new Configuration().addAnnotatedClass(PaperTradeEntity.class)
            .setProperty("hibernate.connection.url",url).setProperty("hibernate.connection.username","sa")
            .setProperty("hibernate.connection.password","").setProperty("hibernate.hbm2ddl.auto","none");
        try(var factory=configuration.buildSessionFactory()){
            String evidence="[\""+ "x".repeat(12000)+"\"]";
            try(var session=factory.openSession()){
                var tx=session.beginTransaction();var trade=new PaperTradeEntity();
                trade.setId("PG-MAPPING-TEST");trade.setSymbol("TEST");trade.setOptionType("CE");trade.setQuantity(1);
                trade.setEntryPremium(BigDecimal.TEN);trade.setStatus("OPEN");trade.setEntryReason("Mapping test");
                trade.setEntryTime(OffsetDateTime.now());trade.setEvidenceJson(evidence);trade.setTimelineJson("[]");
                session.persist(trade);tx.commit();
            }
            try(var session=factory.openSession()){
                assertThat(session.find(PaperTradeEntity.class,"PG-MAPPING-TEST").getEvidenceJson()).isEqualTo(evidence);
            }
        }
    }
}
