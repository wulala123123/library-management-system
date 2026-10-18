package com.library.catalogsets;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.*;
final class CatalogSetJdbc extends JdbcTemplate {
    CatalogSetJdbc(DataSource s){super(s);setQueryTimeout(10);}
    @Override protected void applyStatementSettings(Statement s)throws SQLException{super.applyStatementSettings(s);if(s.getQueryTimeout()==0||s.getQueryTimeout()>10)s.setQueryTimeout(10);}
}
