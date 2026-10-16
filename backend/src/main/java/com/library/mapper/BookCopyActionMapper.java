package com.library.mapper;

import com.library.dto.BookCopyActionContracts.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** Deliberately exposes only append and read operations for lifecycle events. */
@Repository @RequiredArgsConstructor
public class BookCopyActionMapper {
    private final JdbcTemplate jdbc;
    public Long append(Long requestId,String action,String reason,Long operatorId,LocalDateTime time,Long flow,CopyChange c){
        GeneratedKeyHolder key=new GeneratedKeyHolder();
        jdbc.update(connection->{var ps=connection.prepareStatement("INSERT INTO book_copy_action(request_id,ordinal,copy_id,book_id,barcode_snapshot,action_type,before_status,after_status,before_location,after_location,before_version,after_version,before_state_action_id,delta_total,delta_available,reason,operator_id,occurred_at,stock_flow_id,restores_action_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",new String[]{"id"});
            Object[] values={requestId,c.ordinal(),c.copyId(),c.bookId(),c.barcode(),action,c.beforeStatus(),c.afterStatus(),c.beforeLocation(),c.afterLocation(),c.beforeVersion(),c.afterVersion(),c.beforeStateActionId(),c.deltaTotal(),c.deltaAvailable(),reason,operatorId,time,flow,c.restoreOfActionId()};for(int i=0;i<values.length;i++)ps.setObject(i+1,values[i]);return ps;},key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    public ActionEvent read(Long id){var rows=jdbc.query(select()+" WHERE a.id=?",this::event,id);return rows.isEmpty()?null:rows.get(0);}
    public List<ActionEvent> history(Long copyId,int size,long offset){return jdbc.query(select()+" WHERE a.copy_id=? ORDER BY a.id DESC LIMIT ? OFFSET ?",this::event,copyId,size,offset);}
    public long count(Long copyId){return jdbc.queryForObject("SELECT COUNT(*) FROM book_copy_action WHERE copy_id=?",Long.class,copyId);}
    private String select(){return "SELECT a.*,(SELECT restored.id FROM book_copy_action restored WHERE restored.restores_action_id=a.id) AS restored_by FROM book_copy_action a";}
    private ActionEvent event(ResultSet r,int n)throws SQLException{return new ActionEvent(r.getLong("id"),r.getLong("request_id"),r.getString("action_type"),r.getString("reason"),r.getLong("operator_id"),r.getObject("occurred_at",LocalDateTime.class).toInstant(ZoneOffset.UTC).toString(),r.getObject("stock_flow_id",Long.class),r.getObject("restored_by",Long.class),r.getInt("ordinal"),r.getLong("copy_id"),r.getLong("book_id"),r.getString("barcode_snapshot"),r.getString("before_status"),r.getString("after_status"),r.getString("before_location"),r.getString("after_location"),r.getLong("before_version"),r.getLong("after_version"),r.getObject("before_state_action_id",Long.class),r.getObject("restores_action_id",Long.class),r.getInt("delta_total"),r.getInt("delta_available"));}
}
