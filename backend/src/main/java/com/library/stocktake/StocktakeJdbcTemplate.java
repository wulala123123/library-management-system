package com.library.stocktake;
import javax.sql.DataSource;
import org.springframework.jdbc.core.*;
import java.sql.*;
import java.util.*;
/** Keep MySQL TEXT and H2 CLOB values identical at the module boundary. */
final class StocktakeJdbcTemplate extends JdbcTemplate {
    StocktakeJdbcTemplate(DataSource source){super(source);setQueryTimeout(3);}
    @Override protected RowMapper<Map<String,Object>>getColumnMapRowMapper(){RowMapper<Map<String,Object>>delegate=super.getColumnMapRowMapper();return (rs,n)->{Map<String,Object>r=delegate.mapRow(rs,n);for(var e:r.entrySet())if(e.getValue() instanceof Clob c)e.setValue(c.getSubString(1,Math.toIntExact(c.length())));return r;};}
}
