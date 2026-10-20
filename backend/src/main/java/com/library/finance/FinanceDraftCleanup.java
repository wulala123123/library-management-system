package com.library.finance;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import static com.library.finance.FinanceSupport.*;

/** Metadata-only expiry; never selects protected draft input and never confirms money. */
@Component
final class FinanceDraftCleanup {
    final FinanceWriteService writer;
    FinanceDraftCleanup(FinanceWriteService writer){this.writer=writer;}
    @Scheduled(fixedDelay=240000,initialDelay=240000)
    public void clean() {
        try {cleanBatch();}catch(RuntimeException ignored) {/* Retry next interval without logging protected SQL/input. */}
    }
    int cleanBatch() {
        var jdbc=writer.jdbcTemplate();LocalDateTime now=writer.readService().now();
        var candidates=jdbc.query("SELECT actor_id,user_id,operation_key FROM member_finance_operation WHERE state IN ('RESERVED','DRAFT_READY') AND draft_expires_at<=? ORDER BY actor_id LIMIT 500",(r,i)->new Candidate(r.getLong(1),r.getLong(2),text(r.getBytes(3))),now);
        int count=0;
        for(var candidate:candidates)count+=writer.transactions().execute(status->{
            jdbc.queryForList("SELECT id FROM sys_user WHERE id IN (?,?) ORDER BY id FOR UPDATE",Long.class,Math.min(candidate.actor,candidate.member),Math.max(candidate.actor,candidate.member));
            var rows=jdbc.query("SELECT id,state,draft_expires_at FROM member_finance_operation WHERE actor_id=? AND operation_key=? FOR UPDATE",(r,i)->new Expiry(text(r.getBytes(1)),r.getString(2),r.getObject(3,LocalDateTime.class)),candidate.actor,bytes(candidate.key));
            LocalDateTime at=writer.readService().now();if(rows.isEmpty()||!java.util.Set.of("RESERVED","DRAFT_READY").contains(rows.get(0).state)||at.isBefore(rows.get(0).expires))return 0;
            String result=obj("state","EXPIRED","resolvedAt",utc(at),"reasonCode","EXPIRED","receipt",null).toString();
            int changed=jdbc.update("UPDATE member_finance_operation SET state='EXPIRED',draft_input_json=NULL,result_json=?,resolved_at=?,updated_at=? WHERE id=? AND state IN ('RESERVED','DRAFT_READY')",result,at,at,bytes(rows.get(0).id));writer.changed(changed);return changed;
        });return count;
    }
    record Candidate(long actor,long member,String key) {}
    record Expiry(String id,String state,LocalDateTime expires) {}
}
