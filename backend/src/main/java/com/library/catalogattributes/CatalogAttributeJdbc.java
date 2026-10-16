package com.library.catalogattributes;
import javax.sql.DataSource;
import org.springframework.jdbc.core.*;
import java.sql.*;
import java.util.*;
final class CatalogAttributeJdbc extends JdbcTemplate {
    CatalogAttributeJdbc(DataSource source){super(source);setQueryTimeout(3);}
    @Override protected void applyStatementSettings(Statement statement)throws SQLException{super.applyStatementSettings(statement);int timeout=statement.getQueryTimeout();if(timeout==0||timeout>3)statement.setQueryTimeout(3);}
    @Override protected RowMapper<Map<String,Object>> getColumnMapRowMapper(){RowMapper<Map<String,Object>> delegate=super.getColumnMapRowMapper();return (rs,n)->{Map<String,Object> row=delegate.mapRow(rs,n);for(var e:row.entrySet())if(e.getValue() instanceof Clob c)e.setValue(c.getSubString(1,Math.toIntExact(c.length())));return row;};}
}
