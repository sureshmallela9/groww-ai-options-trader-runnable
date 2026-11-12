package com.optionstrader.platform;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.io.Reader;
import org.junit.jupiter.api.Test;

class JsonParameterTest {
    @Test void oracleUsesClobBindingForLargeEvidence() throws Exception {
        var statement=mock(PreparedStatement.class);var connection=mock(Connection.class);var metadata=mock(DatabaseMetaData.class);
        when(statement.getConnection()).thenReturn(connection);when(connection.getMetaData()).thenReturn(metadata);when(metadata.getDatabaseProductName()).thenReturn("Oracle");
        String payload="{\"data\":\""+"x".repeat(100000)+"\"}";JsonParameter.bind(statement,3,payload);
        var reader=org.mockito.ArgumentCaptor.forClass(Reader.class);verify(statement).setClob(eq(3),reader.capture());
        assertThat(new java.io.BufferedReader(reader.getValue()).readLine()).isEqualTo(payload);verify(statement,never()).setString(anyInt(),anyString());
    }
    @Test void textDatabasesRetainTheirPortableStringBinding() throws Exception {
        var statement=mock(PreparedStatement.class);var connection=mock(Connection.class);var metadata=mock(DatabaseMetaData.class);
        when(statement.getConnection()).thenReturn(connection);when(connection.getMetaData()).thenReturn(metadata);when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        JsonParameter.bind(statement,1,"{}");verify(statement).setString(1,"{}");verify(statement,never()).setClob(anyInt(),any(Reader.class));
    }
}
