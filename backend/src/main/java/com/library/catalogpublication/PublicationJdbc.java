package com.library.catalogpublication;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.*;
final class PublicationJdbc extends JdbcTemplate {
    PublicationJdbc(DataSource source){super(source);setQueryTimeout(3);}
    @Override protected void applyStatementSettings(Statement s)throws SQLException{super.applyStatementSettings(s);int t=s.getQueryTimeout();if(t==0||t>3)s.setQueryTimeout(3);}
}
