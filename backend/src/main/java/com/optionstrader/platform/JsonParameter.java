package com.optionstrader.platform;

import java.io.StringReader;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Explicit Oracle CLOB binding avoids VARCHAR/LONG limits for full analysis evidence. */
final class JsonParameter {
    private JsonParameter() {}
    static void bind(PreparedStatement statement,int index,String value) throws SQLException {
        if("Oracle".equalsIgnoreCase(statement.getConnection().getMetaData().getDatabaseProductName()))
            statement.setClob(index,new StringReader(value));
        else statement.setString(index,value);
    }
}
