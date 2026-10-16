package com.library.catalogidentity;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.*;

/** Transaction timeouts may override a JdbcTemplate default; retain the short lock wait budget. */
final class CatalogIdentityJdbc extends JdbcTemplate {
    CatalogIdentityJdbc(DataSource source){super(source);setQueryTimeout(3);}
    @Override protected void applyStatementSettings(Statement statement)throws SQLException{super.applyStatementSettings(statement);int timeout=statement.getQueryTimeout();if(timeout==0||timeout>3)statement.setQueryTimeout(3);}
}
