package com.library.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.library.finance.FinanceSupport.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class FinanceReadService {
    final JdbcTemplate jdbc; final boolean mysql; final FinanceSource sources;
    static final String ENTRY_COLUMNS="e.id,e.account_id,e.request_id,e.ordinal,e.kind,e.amount,e.opening_received,e.opening_refunded,e.cutoff_at,e.actual_at,e.method_code,e.root_fact_key,e.reverses_entry_id,e.replaces_entry_id,e.business_type,e.business_key,e.policy_digest,e.evidence_json,e.reason,e.operator_id,e.recorded_at";
    public FinanceReadService(DataSource source) throws SQLException {
        jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(5);
        try(var c=source.getConnection()) {mysql=c.getMetaData().getDatabaseProductName().equals("MySQL");}
        sources=new FinanceSource(jdbc,mysql);
    }
    JdbcTemplate jdbcTemplate(){return jdbc;}
    boolean isMysql(){return mysql;}
    void admin(Authentication a) {
        if(a==null||!a.isAuthenticated()||a.getPrincipal()==null||"anonymousUser".equals(a.getPrincipal())) throw failure(401,"UNAUTHENTICATED");
        long id;
        try {id=Long.parseLong(a.getName());}catch(NumberFormatException e){throw failure(403,"ADMIN_REQUIRED");}
        var current=jdbc.query("SELECT role,status,deleted FROM sys_user WHERE id=?",(r,i)->"ADMIN".equals(r.getString(1))&&r.getInt(2)==1&&r.getInt(3)==0,id);
        if(current.size()!=1||!current.get(0)||a.getAuthorities().stream().noneMatch(x->x.getAuthority().equals("ROLE_ADMIN"))) throw failure(403,"ADMIN_REQUIRED");
    }
    long member(String id) {
        long member=id(id,"INVALID_INPUT");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=?",Long.class,member)!=1)throw failure(404,"MEMBER_NOT_FOUND");
        // Existing history remains visible after deletion or role changes.
        return member;
    }
    LocalDateTime now() {
        if(mysql) return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)",LocalDateTime.class);
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
    static void query(MultiValueMap<String,String> q,Set<String> allowed) {
        for(var e:q.entrySet()) {if(!allowed.contains(e.getKey()))throw failure(400,"UNKNOWN_FIELD");if(e.getValue().size()!=1)throw failure(400,"DUPLICATE_FIELD");}
    }
    public JsonNode source(String id,MultiValueMap<String,String> q,Authentication a) {
        admin(a);query(q,Set.of());long member=member(id);return sources.view(member,utc(now()));
    }
    public JsonNode finance(String id,MultiValueMap<String,String> q,Authentication a) {
        admin(a);query(q,Set.of());long member=member(id);LocalDateTime at=now();
        List<JsonNode> balances=new ArrayList<>();
        for(String fund:List.of("MEMBER_FEE","DEPOSIT"))balances.add(balance(member,fund,at));
        long verified=balances.stream().filter(x->"VERIFIED".equals(x.path("openingState").asText())).count();
        return obj("memberId",Long.toString(member),"memberRevision",Long.toString(jdbc.queryForObject("SELECT member_revision FROM sys_user WHERE id=?",Long.class,member)),"currency","CNY","accounts",balances,"asRecordedAt",utc(at),"historicalCoverage",verified==2?"COMPLETE":verified==1?"PARTIAL":"UNKNOWN","source","MEMBER_FINANCE_LEDGER");
    }
    record Account(long id,long revision,Long opening,LocalDateTime cutoff) {}
    Account account(long member,String fund) {
        var rows=jdbc.query("SELECT id,revision,opening_entry_id,scope_cutoff_at FROM member_finance_account WHERE user_id=? AND fund=? AND currency='CNY'",(r,i)->new Account(r.getLong(1),r.getLong(2),(Long)r.getObject(3),r.getObject(4,LocalDateTime.class)),member,fund);
        return rows.isEmpty()?null:rows.get(0);
    }
    record Entry(long id,long account,String kind,BigDecimal amount,BigDecimal received,BigDecimal refunded,LocalDateTime cutoff,LocalDateTime actual,Long reverses,Long replaces,LocalDateTime recorded,ObjectNode value) {}
    Entry entry(ResultSet r,int ignored)throws SQLException {
        var evidence=read(r.getString("evidence_json"));
        if(!evidence.isArray()||evidence.isEmpty()||evidence.size()>20)throw failure(500,"INTERNAL_ERROR");
        List<JsonNode> refs=new ArrayList<>();
        for(var v:evidence) {
            if(!v.isObject()||v.size()!=3||!v.has("type")||!v.has("reference")||!v.has("digest"))throw failure(500,"INTERNAL_ERROR");
            if(!v.path("type").isTextual()||!Set.of("LEGACY_ARCHIVE","COUNTER_VOUCHER","VERIFICATION_NOTE").contains(v.path("type").asText())
                    ||!v.path("reference").isTextual()||v.path("reference").asText().isEmpty()||v.path("reference").asText().codePointCount(0,v.path("reference").asText().length())>128
                    ||!v.path("digest").isNull()&&(!v.path("digest").isTextual()||!v.path("digest").asText().matches("[0-9a-f]{64}")))throw failure(500,"INTERNAL_ERROR");
            refs.add(obj("type",v.get("type"),"reference",v.get("reference"),"digest",v.get("digest")));
        }
        long id=r.getLong("id"),account=r.getLong("account_id");String kind=r.getString("kind");
        if(!Set.of("OPENING","RECEIPT","REFUND","REVERSAL").contains(kind)||r.getInt("ordinal")<1||r.getInt("ordinal")>2)throw failure(500,"INTERNAL_ERROR");
        BigDecimal amount=r.getBigDecimal("amount"),received=r.getBigDecimal("opening_received"),refunded=r.getBigDecimal("opening_refunded");
        LocalDateTime cutoff=r.getObject("cutoff_at",LocalDateTime.class),actual=r.getObject("actual_at",LocalDateTime.class),recorded=r.getObject("recorded_at",LocalDateTime.class);
        Long reverses=(Long)r.getObject("reverses_entry_id"),replaces=(Long)r.getObject("replaces_entry_id");
        ObjectNode value=obj("id",Long.toString(id),"accountId",Long.toString(account),"requestId",text(r.getBytes("request_id")),"ordinal",r.getInt("ordinal"),"kind",kind,"amount",money(amount),"openingReceived",money(received),"openingRefunded",money(refunded),"cutoffAt",utc(cutoff),"actualAt",utc(actual),"methodCode",r.getString("method_code"),"rootFactKey",text(r.getBytes("root_fact_key")),"reversesEntryId",reverses==null?null:reverses.toString(),"replacesEntryId",replaces==null?null:replaces.toString(),"businessRef",obj("type",r.getString("business_type"),"id",text(r.getBytes("business_key"))),"policyDigest",r.getString("policy_digest"),"evidenceRefs",refs,"reason",r.getString("reason"),"operatorId",Long.toString(r.getLong("operator_id")),"recordedAt",utc(recorded));
        return new Entry(id,account,kind,amount,received,refunded,cutoff,actual,reverses,replaces,recorded,value);
    }
    record Allocation(long refund,long lot,BigDecimal amount) {}
    List<Allocation> allocations(long account,LocalDateTime at) {
        return jdbc.query("SELECT a.refund_entry_id,a.lot_entry_id,a.amount FROM member_finance_allocation a JOIN member_finance_entry e ON e.id=a.refund_entry_id AND e.account_id=a.account_id WHERE a.account_id=? AND e.recorded_at<=? ORDER BY a.refund_entry_id,a.lot_entry_id",(r,i)->new Allocation(r.getLong(1),r.getLong(2),r.getBigDecimal(3)),account,at);
    }
    JsonNode balance(long member,String fund,LocalDateTime at) {
        Account account=account(member,fund);List<Entry> entries=account==null?List.of():jdbc.query("SELECT "+ENTRY_COLUMNS+" FROM member_finance_entry e WHERE e.account_id=? AND e.recorded_at<=? ORDER BY e.id",this::entry,account.id,at);
        Map<Long,Entry> byId=new HashMap<>();entries.forEach(e->byId.put(e.id,e));Set<Long> reversed=new HashSet<>();boolean anomaly=false;
        for(Entry e:entries)if(e.kind.equals("REVERSAL")) {
            Entry original=byId.get(e.reverses);
            if(original==null||original.kind.equals("REVERSAL")||original.amount.compareTo(e.amount)!=0||original.id>=e.id||!reversed.add(e.reverses))anomaly=true;
        }
        List<Entry> active=entries.stream().filter(e->!e.kind.equals("REVERSAL")&&!reversed.contains(e.id)).toList();
        for(Entry e:entries)if(e.replaces!=null) {
            Entry original=byId.get(e.replaces);
            if(original==null||!original.kind.equals(e.kind)||!reversed.contains(original.id)||original.id>=e.id)anomaly=true;
        }
        List<Entry> openings=active.stream().filter(e->e.kind.equals("OPENING")).toList();Entry opening=openings.size()==1?openings.get(0):null;
        if(openings.size()>1||account!=null&&((account.opening==null)!=(opening==null)||opening!=null&&(!Objects.equals(account.opening,opening.id)||!Objects.equals(account.cutoff,opening.cutoff))))anomaly=true;
        BigDecimal receipts=ZERO,refunds=ZERO;
        for(Entry e:active) {
            if(e.kind.equals("RECEIPT"))receipts=receipts.add(e.amount);
            if(e.kind.equals("REFUND"))refunds=refunds.add(e.amount);
            if((e.kind.equals("RECEIPT")||e.kind.equals("REFUND"))&&(account.cutoff==null||e.actual==null||e.actual.isBefore(account.cutoff)))anomaly=true;
        }
        Map<Long,BigDecimal> refundAllocated=new HashMap<>(),lotAllocated=new HashMap<>();
        for(Allocation allocation:account==null?List.<Allocation>of():allocations(account.id,at)) {
            Entry refund=byId.get(allocation.refund),lot=byId.get(allocation.lot);
            if(refund==null||!refund.kind.equals("REFUND")||lot==null||!Set.of("OPENING","RECEIPT").contains(lot.kind)||allocation.amount.signum()<=0) {anomaly=true;continue;}
            refundAllocated.merge(refund.id,allocation.amount,BigDecimal::add);
            if(!reversed.contains(refund.id)) {if(reversed.contains(lot.id))anomaly=true;lotAllocated.merge(lot.id,allocation.amount,BigDecimal::add);}
        }
        for(Entry e:entries)if(e.kind.equals("REFUND")&&e.amount.compareTo(refundAllocated.getOrDefault(e.id,ZERO))!=0)anomaly=true;
        for(var e:lotAllocated.entrySet())if(e.getValue().compareTo(byId.get(e.getKey()).amount)>0)anomaly=true;
        BigDecimal paid=opening==null?ZERO:opening.received,returned=opening==null?ZERO:opening.refunded;
        if(opening!=null&&(paid==null||returned==null||paid.signum()<0||returned.signum()<0||paid.subtract(returned).compareTo(opening.amount)!=0))anomaly=true;
        boolean verified=opening!=null&&!anomaly;
        BigDecimal totalPaid=paid==null?ZERO:paid.add(receipts),totalRefunded=returned==null?ZERO:returned.add(refunds),balance=totalPaid.subtract(totalRefunded);
        if(balance.signum()<0&&opening!=null) {anomaly=true;verified=false;}
        JsonNode unknown=unknown(anomaly?"LEDGER_ANOMALY":"OPENING_UNVERIFIED");
        return obj("memberId",Long.toString(member),"accountId",account==null?null:Long.toString(account.id),"fund",fund,"currency","CNY","financeRevision",account==null?"0":Long.toString(account.revision),"scopeCutoffAt",account==null?null:utc(account.cutoff),"openingEntryId",account==null||account.opening==null?null:account.opening.toString(),"openingState",verified?"VERIFIED":"UNKNOWN","verifiedPaid",verified?known(totalPaid):unknown,"verifiedRefunded",verified?known(totalRefunded):unknown,"balance",verified?known(balance):unknown,"feeHeadroom",fund.equals("DEPOSIT")?null:verified?known(balance):unknown,"newReceipts",money(receipts),"newRefunds",money(refunds),"newNet",money(receipts.subtract(refunds)),"asRecordedAt",utc(at));
    }
    public JsonNode entries(String id,MultiValueMap<String,String> q,Authentication a) {
        admin(a);query(q,Set.of("fund","currency","beforeId","size","asRecordedAt"));long member=member(id);
        String fund=q.getFirst("fund"),currency=q.getFirst("currency"),before=q.getFirst("beforeId"),as=q.getFirst("asRecordedAt"),sizeText=q.getFirst("size");
        if(!Set.of("MEMBER_FEE","DEPOSIT").contains(fund==null?"":fund)||!"CNY".equals(currency))throw failure(400,"INVALID_INPUT");
        if(sizeText!=null&&!sizeText.matches("[1-9][0-9]{0,2}"))throw failure(400,"INVALID_INPUT");
        int size=sizeText==null?20:Integer.parseInt(sizeText);if(size>100)throw failure(400,"INVALID_INPUT");
        Long beforeId=before==null?null:id(before,"BAD_CURSOR");LocalDateTime now=now(),at=as==null?now:utcInput(as);
        if(at.isAfter(now)||beforeId!=null&&as==null)throw failure(400,"BAD_CURSOR");
        Account account=account(member,fund);List<Entry> rows=List.of();
        if(beforeId!=null&&(account==null||jdbc.queryForObject("SELECT COUNT(*) FROM member_finance_entry WHERE id=? AND account_id=? AND recorded_at<=?",Long.class,beforeId,account.id,at)!=1))throw failure(400,"BAD_CURSOR");
        if(account!=null)rows=jdbc.query("SELECT "+ENTRY_COLUMNS+" FROM member_finance_entry e WHERE e.account_id=? AND e.recorded_at<=?"+(beforeId==null?"":" AND e.id<?")+" ORDER BY e.id DESC LIMIT ?",this::entry,beforeId==null?new Object[]{account.id,at,size+1}:new Object[]{account.id,at,beforeId,size+1});
        boolean more=rows.size()>size;if(more)rows=rows.subList(0,size);
        Map<Long,Long> reversed=new HashMap<>();Map<Long,List<JsonNode>> allocated=new HashMap<>();
        if(!rows.isEmpty()) {
            String placeholders=String.join(",",Collections.nCopies(rows.size(),"?"));List<Object> args=new ArrayList<>();args.add(account.id);args.add(at);rows.forEach(e->args.add(e.id));
            jdbc.query("SELECT reverses_entry_id,id FROM member_finance_entry WHERE account_id=? AND recorded_at<=? AND kind='REVERSAL' AND reverses_entry_id IN ("+placeholders+")",r->{reversed.put(r.getLong(1),r.getLong(2));},args.toArray());
            List<Object> allocArgs=new ArrayList<>();allocArgs.add(account.id);rows.forEach(e->allocArgs.add(e.id));
            jdbc.query("SELECT refund_entry_id,lot_entry_id,amount FROM member_finance_allocation WHERE account_id=? AND refund_entry_id IN ("+placeholders+") ORDER BY refund_entry_id,lot_entry_id",r->{allocated.computeIfAbsent(r.getLong(1),ignored->new ArrayList<>()).add(obj("lotEntryId",Long.toString(r.getLong(2)),"amount",money(r.getBigDecimal(3))));},allocArgs.toArray());
        }
        List<JsonNode> values=new ArrayList<>();
        for(Entry e:rows) {var v=e.value;Long reversal=reversed.get(e.id);v.put("asOf",utc(at));v.put("effectiveAtAsOf",!e.kind.equals("REVERSAL")&&reversal==null);v.set("reversalEntryId",JSON.valueToTree(reversal==null?null:reversal.toString()));v.set("allocations",JSON.valueToTree(allocated.getOrDefault(e.id,List.of())));values.add(v);}
        return obj("memberId",Long.toString(member),"fund",fund,"currency","CNY","asRecordedAt",utc(at),"beforeId",before,"nextBeforeId",more?Long.toString(rows.get(rows.size()-1).id):null,"size",size,"hasMore",more,"entries",values);
    }
}
