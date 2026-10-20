package com.library.finance;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import static com.library.finance.FinanceSupport.*;
import static com.library.finance.FinanceCommands.canonicalHash;

/** Runtime only reads the deployment activation anchor; no implicit initialization. */
@Component
final class FinanceReceiptPolicy {
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    final Environment environment;final FinanceReadService read;
    FinanceReceiptPolicy(Environment environment,FinanceReadService read){this.environment=environment;this.read=read;}
    record Snapshot(JsonNode policy,JsonNode activation,LocalDateTime cutoff,boolean enabled) {}
    Snapshot snapshot(LocalDateTime now) {
        boolean enabled="true".equals(environment.getProperty("library.finance.receipts.enabled","false"));
        String configured=environment.getProperty("library.finance.receipts.start-date","");LocalDate date=null;LocalDateTime cutoff=null;
        try {if(!configured.matches("[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}"))throw new DateTimeException("date");date=LocalDate.parse(configured);cutoff=date.atStartOfDay(ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();if(cutoff.getYear()<1000)throw new DateTimeException("range");}catch(DateTimeException e){enabled=false;date=null;cutoff=null;}
        JsonNode anchor=null;
        if(enabled) {
            var rows=read.jdbcTemplate().query("SELECT policy_key,activation_date,cutoff_at,financial_time_zone,created_at FROM member_finance_activation WHERE policy_key='B40_RECEIPT_V1'",(r,i)->obj("policyKey",r.getString(1),"activationDate",r.getObject(2,LocalDate.class).toString(),"cutoffAt",utc(r.getObject(3,LocalDateTime.class)),"financialTimeZone",r.getString(4),"createdAt",utc(r.getObject(5,LocalDateTime.class))));
            if(rows.size()==1)anchor=rows.get(0);
            enabled=anchor!=null&&anchor.path("activationDate").asText().equals(configured)&&anchor.path("cutoffAt").asText().equals(utc(cutoff))&&anchor.path("financialTimeZone").asText().equals(ZONE.getId())&&!now.isBefore(cutoff);
        }
        JsonNode p=obj("currency","CNY","scale",2,"rounding","REJECT_EXTRA_SCALE","businessAmountLimit",null,"paymentMethodsState","CONFIRMED","paymentMethodCodes",List.of("CASH","WECHAT"),"datePolicyState",date==null?"UNCONFIRMED":"CONFIRMED","cutoffAt",utc(cutoff),"financialTimeZone",date==null?null:ZONE.getId(),"backdateRuleRef",date==null?null:"B40B_D03_SAME_SHANGHAI_DAY_V1","debtPolicyState","UNCONFIRMED","debtPolicyVersion",null,"enabledActions",enabled?List.of("RECEIPT"):List.of());
        ((com.fasterxml.jackson.databind.node.ObjectNode)p).put("digest",canonicalHash(p));
        return new Snapshot(p,obj("configuredEnabled",environment.getProperty("library.finance.receipts.enabled","false"),"configuredStartDate",configured,"anchor",anchor,"D01","CONFIRMED","D02","CONFIRMED","D03","CONFIRMED","D04","UNCONFIRMED","D06","UNCONFIRMED"),cutoff,enabled);
    }
}
