package com.library.circulation;

import org.springframework.jdbc.core.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.Map;

/** DATETIME stores UTC wall time and DATE stores a calendar date, without a JVM zone conversion. */
final class CirculationJdbc extends JdbcTemplate {
    CirculationJdbc(DataSource source){super(source);setQueryTimeout(5);}
    @Override protected RowMapper<Map<String,Object>> getColumnMapRowMapper(){
        return new ColumnMapRowMapper(){
            @Override protected Object getColumnValue(ResultSet rs,int index)throws SQLException {
                int type=rs.getMetaData().getColumnType(index);
                if(type==Types.DATE)return rs.getObject(index,LocalDate.class);
                if(type==Types.TIMESTAMP)return rs.getObject(index,LocalDateTime.class);
                Object value=super.getColumnValue(rs,index);
                return value instanceof Clob c?c.getSubString(1,Math.toIntExact(c.length())):value;
            }
        };
    }
}
