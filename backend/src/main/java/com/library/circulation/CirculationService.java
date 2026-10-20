package com.library.circulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.library.service.CopyInventoryProjection;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import static com.library.circulation.CirculationSupport.*;

/** Counter-only atomic batches; borrow_record remains the circulation source of truth. */
@Service
public class CirculationService {
    final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final CopyInventoryProjection projection;
    volatile Clock clock=Clock.systemUTC();

    public CirculationService(JdbcTemplate jdbc,PlatformTransactionManager transactions,CopyInventoryProjection projection) {
        this.jdbc=new CirculationJdbc(Objects.requireNonNull(jdbc.getDataSource()));this.transactions=transactions;this.projection=projection;
    }
    Instant now(){return clock.instant().truncatedTo(ChronoUnit.SECONDS);}
    private LocalDate day(Instant at){return at.atZone(ZONE).toLocalDate();}
    private <T>T tx(boolean read,Supplier<T> work) {
        TransactionTemplate t=new TransactionTemplate(transactions);
        t.setIsolationLevel(read?TransactionDefinition.ISOLATION_REPEATABLE_READ:TransactionDefinition.ISOLATION_READ_COMMITTED);
        t.setReadOnly(read);t.setTimeout(15);
        try {return t.execute(status->work.get());}
        catch(DataAccessException e) {
            Throwable x=e;while(x!=null){if(x instanceof SQLException s&&(s.getErrorCode()==1205||s.getErrorCode()==1213||s.getErrorCode()==3572||s.getErrorCode()==50200||"40001".equals(s.getSQLState())))throw conflict("CIRCULATION_BUSY");x=x.getCause();}
            if(e instanceof PessimisticLockingFailureException||e instanceof QueryTimeoutException)throw conflict("CIRCULATION_BUSY");
            throw fail(500,"CIRCULATION_SAVE_FAILED");
        } catch(TransactionTimedOutException e){throw conflict("CIRCULATION_BUSY");}
        catch(Failure e){throw e;}
        catch(RuntimeException e){throw fail(500,"CIRCULATION_SAVE_FAILED");}
    }
    public void authorize(long actor) {
        safe(actor);var rows=jdbc.queryForList("SELECT role,status,deleted FROM sys_user WHERE id=?",actor);
        if(rows.isEmpty()||num(rows.get(0),"status")!=1||num(rows.get(0),"deleted")!=0||!Set.of("ADMIN","LIBRARIAN").contains(str(rows.get(0),"role")))throw fail(403,"ROLE_REQUIRED");
    }
    private void lockUsers(Collection<Long> ids,long actor) {
        TreeSet<Long> sorted=new TreeSet<>(ids);sorted.add(actor);
        for(long id:sorted)jdbc.queryForList("SELECT id FROM sys_user WHERE id=? FOR UPDATE",id);
        authorize(actor);
    }
    private Map<String,Object> request(String key,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM circulation_request WHERE operation_key=?"+(lock?" FOR UPDATE":""),bytes(key));
        return rows.isEmpty()?null:rows.get(0);
    }
    private void owned(Map<String,Object> r,long actor) {
        if(r==null)throw fail(404,"REQUEST_NOT_FOUND");if(num(r,"actor_id")!=actor)throw fail(403,"OWNER_REQUIRED");
    }
    private void reserve(String key,long actor,String state) {
        Instant at=now();
        jdbc.update("INSERT INTO circulation_request(operation_key,actor_id,state,created_at,cancelled_at) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE operation_key=operation_key",
                bytes(key),actor,state,local(at),state.equals("CANCELLED")?local(at):null);
    }
    private JsonNode view(Map<String,Object> r) {
        return obj("schemaVersion",1,"operationKey",new String((byte[])r.get("operation_key"),java.nio.charset.StandardCharsets.UTF_8),
                "state",r.get("state"),"operation",r.get("operation"),"actorId",safe(num(r,"actor_id")),
                "createdAt",utc(r.get("created_at")),"expiresAt",utc(r.get("expires_at")),"businessDate",date(r.get("business_date")),
                "input",json(r.get("request_json")),"previewDigest",r.get("preview_digest"),"preview",json(r.get("preview_json")),
                "receipt",json(r.get("receipt_json")),"cancelledAt",utc(r.get("cancelled_at")));
    }
    public JsonNode get(String rawKey,long actor) {
        authorize(actor);String key=key(rawKey);return tx(true,()->{authorize(actor);var r=request(key,false);owned(r,actor);return view(r);});
    }
    public JsonNode preview(String rawKey,JsonNode raw,long actor) {
        authorize(actor);String key=key(rawKey);ObjectNode in=input(raw);String inputHash=hash(in);
        return tx(false,()->{
            authorize(actor);reserve(key,actor,"RESERVED");var r=request(key,true);owned(r,actor);
            if(!r.get("state").equals("RESERVED")) {
                if(r.get("input_hash")==null)throw conflict("REQUEST_CANCELLED");
                if(!inputHash.equals(r.get("input_hash")))throw conflict("IDEMPOTENCY_KEY_REUSED");
                return view(r);
            }
            Plan p=plan(in,actor,null);Instant at=now();
            if(!day(at).toString().equals(p.preview.path("businessDate").asText()))throw conflict("PREVIEW_STALE");
            String digest=hash(obj("input",in,"preview",p.preview,"guard",p.guard));
            changed(jdbc.update("UPDATE circulation_request SET operation=?,state='PREVIEWED',input_hash=?,request_json=?,preview_json=?,guard_json=?,preview_digest=?,business_date=?,created_at=?,expires_at=? WHERE operation_key=? AND state='RESERVED'",
                    in.path("operation").asText(),inputHash,write(in),write(p.preview),write(p.guard),digest,day(at),local(at),local(at.plusSeconds(900)),bytes(key)));
            return view(request(key,false));
        });
    }
    public JsonNode cancel(String rawKey,JsonNode raw,long actor) {
        authorize(actor);String key=key(rawKey);keys(raw,"");
        return tx(false,()->{
            authorize(actor);reserve(key,actor,"CANCELLED");var r=request(key,true);owned(r,actor);lockUsers(List.of(),actor);
            if(r.get("state").equals("COMMITTED")||r.get("state").equals("CANCELLED"))return view(r);
            changed(jdbc.update("UPDATE circulation_request SET state='CANCELLED',cancelled_at=? WHERE operation_key=? AND state='PREVIEWED'",local(now()),bytes(key)));
            return view(request(key,false));
        });
    }
    public JsonNode confirm(String rawKey,JsonNode raw,long actor) {
        authorize(actor);String key=key(rawKey);keys(raw,"previewDigest");String digest=digest(raw.get("previewDigest"));
        return tx(false,()->{
            authorize(actor);var r=request(key,true);owned(r,actor);
            if(r.get("state").equals("CANCELLED"))throw conflict("REQUEST_CANCELLED");
            if(!digest.equals(r.get("preview_digest")))throw conflict("IDEMPOTENCY_KEY_REUSED");
            if(r.get("state").equals("COMMITTED")){lockUsers(List.of(),actor);return json(r.get("receipt_json"));}
            ObjectNode in=(ObjectNode)json(r.get("request_json"));LocalDate business=LocalDate.parse(date(r.get("business_date")));
            Plan p;
            try {p=plan(in,actor,business);}catch(Failure e){if(e.status==403)throw e;throw conflict("PREVIEW_STALE");}
            validTime(r); // after all business locks, before any mutation
            if(!digest.equals(hash(obj("input",in,"preview",p.preview,"guard",p.guard))))throw conflict("PREVIEW_STALE");
            boolean borrow=in.path("operation").asText().equals("BORROW");Long orderId=null;Instant at=now();
            if(borrow)orderId=insert("INSERT INTO circulation_order(request_key,user_id,actor_id,created_at,business_date,item_count,remark) VALUES (?,?,?,?,?,?,?)",
                    bytes(key),in.path("userId").asLong(),actor,local(at),business,in.path("items").size(),in.path("remark").asText());
            ArrayNode receiptItems=JSON.createArrayNode();
            for(JsonNode item:p.preview.path("items")) {
                long recordId;
                Long copyId=nullable(item.get("copyId"));long bookId=item.path("bookId").asLong();
                if(borrow) {
                    recordId=insert("INSERT INTO borrow_record(user_id,book_id,copy_id,borrow_date,due_date,due_date_kind,renew_count,status,remark) VALUES (?,?,?,?,?,'DATED',0,'BORROWING',?)",
                            item.path("userId").asLong(),bookId,copyId,business,LocalDate.parse(item.path("dueDate").asText()),in.path("remark").asText());
                    changed(jdbc.update("INSERT INTO circulation_order_item(order_id,ordinal,record_id,book_id,copy_id,book_title,barcode) VALUES (?,?,?,?,?,?,?)",orderId,item.path("ordinal").asInt(),recordId,bookId,copyId,item.path("bookTitle").asText(),item.path("barcode").asText()));
                } else {
                    recordId=item.path("recordId").asLong();
                    changed(jdbc.update("UPDATE borrow_record SET status='RETURNED',return_date=?,update_time=CURRENT_TIMESTAMP WHERE id=? AND deleted=0 AND status IN ('BORROWING','OVERDUE')",business,recordId));
                }
                if(copyId!=null)changed(jdbc.update("UPDATE book_copy SET status=?,action_version=action_version+1 WHERE id=? AND action_version=? AND action_version<9223372036854775807",
                        item.path("afterCopyStatus").asText(),copyId,item.path("copyVersionBefore").asLong()));
                long flowId=insert("INSERT INTO stock_flow(book_id,flow_type,quantity,related_id,operator_id,remark) VALUES (?,?,1,?,?,?)",bookId,borrow?"BORROW":"RETURN",recordId,actor,in.path("remark").asText());
                ObjectNode result=item.deepCopy();result.put("recordId",recordId);result.put("stockFlowId",flowId);receiptItems.add(result);
            }
            for(JsonNode b:p.preview.path("books"))changed(jdbc.update("UPDATE book SET stock_available=?,update_time=CURRENT_TIMESTAMP WHERE id=? AND deleted=0 AND stock_total=? AND stock_available=?",
                    b.path("afterAvailable").asInt(),b.path("bookId").asLong(),b.path("stockTotal").asInt(),b.path("stockAvailable").asInt()));
            validTime(r); // a request crossing midnight/expiry while writing rolls back, too
            JsonNode receipt=obj("schemaVersion",1,"operationKey",key,"operation",in.get("operation"),"actorId",actor,"confirmedAt",now().toString(),"businessDate",business.toString(),"orderId",orderId,"previewDigest",digest,"items",receiptItems,"books",p.preview.get("books"));
            changed(jdbc.update("UPDATE circulation_request SET state='COMMITTED',committed_at=?,receipt_json=? WHERE operation_key=? AND state='PREVIEWED'",local(now()),write(receipt),bytes(key)));
            return read(write(receipt));
        });
    }
    private void validTime(Map<String,Object> r) {
        Instant at=now();if(!at.isBefore(Instant.parse(utc(r.get("expires_at"))))||!day(at).toString().equals(date(r.get("business_date"))))throw conflict("PREVIEW_EXPIRED");
    }
    private record Plan(ObjectNode preview,JsonNode guard) {}
    private Plan plan(ObjectNode in,long actor,LocalDate fixedDay) {
        boolean borrow=in.path("operation").asText().equals("BORROW");
        TreeSet<Long> users=new TreeSet<>(),bookIds=new TreeSet<>(),copyIds=new TreeSet<>(),recordIds=new TreeSet<>();
        Map<Long,Map<String,Object>> initialRecords=new TreeMap<>();
        if(borrow) {users.add(in.path("userId").asLong());for(JsonNode item:in.path("items")){bookIds.add(item.path("bookId").asLong());copyIds.add(item.path("copyId").asLong());}}
        else for(JsonNode id:in.path("recordIds")) {
            long n=id.asLong();var row=one("SELECT * FROM borrow_record WHERE id=? AND deleted=0","RECORD_NOT_FOUND",n);
            initialRecords.put(n,row);recordIds.add(n);users.add(safe(num(row,"user_id")));bookIds.add(safe(num(row,"book_id")));if(row.get("copy_id")!=null)copyIds.add(safe(num(row,"copy_id")));
        }
        lockUsers(users,actor);
        Map<Long,Map<String,Object>> books=new TreeMap<>(),records=new TreeMap<>(),copies=new TreeMap<>();
        for(long id:bookIds)books.put(id,one("SELECT * FROM book WHERE id=? AND deleted=0 FOR UPDATE","BOOK_NOT_FOUND",id));
        for(long id:recordIds) {
            var row=one("SELECT * FROM borrow_record WHERE id=? AND deleted=0 FOR UPDATE","RECORD_NOT_FOUND",id);
            for(String f:List.of("user_id","book_id","copy_id"))if(!Objects.equals(row.get(f),initialRecords.get(id).get(f)))throw conflict("PREVIEW_STALE");records.put(id,row);
        }
        for(long id:copyIds)copies.put(id,one("SELECT * FROM book_copy WHERE id=? FOR UPDATE","COPY_NOT_FOUND",id));
        LocalDate date=fixedDay==null?day(now()):fixedDay;
        ArrayNode items=JSON.createArrayNode(),bookChanges=JSON.createArrayNode(),guard=JSON.createArrayNode(),warnings=JSON.createArrayNode();
        Map<Long,Integer> deltas=new TreeMap<>();JsonNode borrower=null;Long beforeCount=null,afterCount=null;
        if(borrow) {
            long userId=in.path("userId").asLong();var u=one("SELECT id,real_name,role,status,deleted,member_revision FROM sys_user WHERE id=? AND deleted=0","MEMBER_NOT_FOUND",userId);
            if(!"READER".equals(u.get("role")))throw fail(404,"MEMBER_NOT_FOUND");
            if(num(u,"status")!=1)throw conflict("MEMBER_DISABLED");
            var active=jdbc.queryForList("SELECT id,book_id,copy_id,status,due_date,renew_count FROM borrow_record WHERE user_id=? AND deleted=0 AND status IN ('BORROWING','OVERDUE') ORDER BY id",userId);
            beforeCount=(long)active.size();afterCount=beforeCount+in.path("items").size();if(afterCount>10)throw conflict("BORROW_LIMIT");
            Set<Long> activeBooks=new HashSet<>();for(var r:active)activeBooks.add(num(r,"book_id"));
            var cards=jdbc.queryForList("SELECT card_no FROM member_profile WHERE user_id=?",userId);String card=cards.isEmpty()?null:str(cards.get(0),"card_no");
            borrower=obj("userId",userId,"userName",u.get("real_name"),"cardNo",card,"status",u.get("status"));
            guard.add(obj("member",obj("id",userId,"realName",u.get("real_name"),"role",u.get("role"),"status",u.get("status"),"deleted",u.get("deleted"),"memberRevision",u.get("member_revision")),"active",normalizedRows(active),"cardNo",card));
            int ordinal=0;
            for(JsonNode target:in.path("items")) {
                long bookId=target.path("bookId").asLong(),copyId=target.path("copyId").asLong();var book=books.get(bookId);var copy=copies.get(copyId);
                if(activeBooks.contains(bookId))throw conflict("DUPLICATE_BOOK");if(num(book,"status")!=1)throw conflict("BOOK_UNAVAILABLE");
                checkCopy(copy,bookId,"AVAILABLE",null);
                String title=display(bookId,book);long version=version(copy);
                items.add(item(++ordinal,null,userId,str(u,"real_name"),bookId,title,copyId,str(copy,"barcode"),null,"BORROWING","AVAILABLE","BORROWED",version,version+1,date.toString(),date.plusDays(target.path("borrowDays").asLong()).toString(),"DATED",null,0));
                deltas.merge(bookId,-1,Integer::sum);
            }
        } else {
            int ordinal=0;
            for(JsonNode id:in.path("recordIds")) {
                long recordId=id.asLong();var r=records.get(recordId);String status=str(r,"status");
                if(!Set.of("BORROWING","OVERDUE").contains(status))throw conflict("RECORD_NOT_ACTIVE");
                long userId=num(r,"user_id"),bookId=num(r,"book_id");Long copyId=r.get("copy_id")==null?null:num(r,"copy_id");
                var names=jdbc.queryForList("SELECT real_name FROM sys_user WHERE id=?",userId);String name=names.isEmpty()?null:str(names.get(0),"real_name");
                Map<String,Object> c=copyId==null?null:copies.get(copyId);Long v=c==null?null:version(c);if(c!=null)checkCopy(c,bookId,"BORROWED",recordId);
                Object renew=r.get("renew_count");if(renew==null||((Number)renew).longValue()<0||((Number)renew).longValue()>MAX)throw conflict("UNSAFE_STORED_VALUE");
                items.add(item(++ordinal,recordId,userId,name,bookId,display(bookId,books.get(bookId)),copyId,c==null?null:str(c,"barcode"),status,"RETURNED",c==null?null:"BORROWED",c==null?null:"AVAILABLE",v,v==null?null:v+1,date(r.get("borrow_date")),date(r.get("due_date")),str(r,"due_date_kind"),date.toString(),((Number)renew).intValue()));
                guard.add(obj("record",loanGuard(r)));deltas.merge(bookId,1,Integer::sum);
            }
        }
        Map<Long,CopyInventoryProjection.V2> projections=projection.forBooks(bookIds);
        for(var entry:books.entrySet()) {
            long id=entry.getKey();var b=entry.getValue();long total=num(b,"stock_total"),available=num(b,"stock_available"),after=available+deltas.get(id);
            if(total<0||available<0||available>total||after<0||after>total||after>Integer.MAX_VALUE)throw conflict("STOCK_UNAVAILABLE");
            bookChanges.add(obj("bookId",id,"stockTotal",total,"stockAvailable",available,"deltaAvailable",deltas.get(id),"afterAvailable",after));
            var attributes=jdbc.queryForList("SELECT revision FROM catalog_book_attribute WHERE book_id=?",id);
            guard.add(obj("bookId",id,"title",b.get("title"),"status",b.get("status"),"stockTotal",total,"stockAvailable",available,"attributeRevision",attributes.isEmpty()?null:attributes.get(0).get("revision"),"publicationRevision",b.get("publication_revision"),"display",display(id,b)));
            var p=projections.get(id);if(p.hasHistoricalDifference())addWarning(warnings,"HISTORICAL_STOCK_DIFFERENCE");if(p.hasUnknownStatus())addWarning(warnings,"UNKNOWN_LEGACY_STATUS");if(p.hasLoanLinkAnomaly())addWarning(warnings,"COPY_LINK_ANOMALY");
        }
        for(var entry:copies.entrySet()) {var c=entry.getValue();guard.add(obj("copyId",entry.getKey(),"bookId",c.get("book_id"),"barcode",c.get("barcode"),"status",c.get("status"),"version",c.get("action_version"),"location",c.get("location"),"registeredLocationId",c.get("registered_location_id"),"lastLocationActionId",c.get("last_location_action_id")));}
        return new Plan(obj("schemaVersion",1,"operation",in.get("operation"),"businessDate",date.toString(),"borrower",borrower,"items",items,"books",bookChanges,"activeCountBefore",beforeCount,"activeCountAfter",afterCount,"warnings",warnings),canonical(guard));
    }
    private void addWarning(ArrayNode a,String code){for(JsonNode n:a)if(n.asText().equals(code))return;a.add(code);}
    private JsonNode loanGuard(Map<String,Object> r){ObjectNode n=obj();for(String f:List.of("id","user_id","book_id","copy_id","status","due_date_kind","borrow_date","due_date","return_date","renew_count","deleted","source_key")){Object v=r.get(f);n.set(f,tree(v instanceof java.sql.Date||v instanceof LocalDate?date(v):v));}return n;}
    private ArrayNode normalizedRows(List<Map<String,Object>> rows){ArrayNode out=JSON.createArrayNode();for(var r:rows){ObjectNode o=obj();r.forEach((k,v)->o.set(k,tree(v instanceof java.sql.Date||v instanceof LocalDate?date(v):v)));out.add(o);}return out;}
    private void checkCopy(Map<String,Object> c,long book,String expected,Long record) {
        if(num(c,"book_id")!=book||!expected.equals(c.get("status")))throw conflict("COPY_UNAVAILABLE");
        var links=jdbc.queryForList("SELECT id,book_id FROM borrow_record WHERE copy_id=? AND deleted=0 AND status IN ('BORROWING','OVERDUE') ORDER BY id",c.get("id"));
        if(record==null?!links.isEmpty():links.size()!=1||num(links.get(0),"id")!=record||num(links.get(0),"book_id")!=book)throw conflict("COPY_LINK_ANOMALY");
    }
    private long version(Map<String,Object> c){long v=num(c,"action_version");if(v<0||v>=MAX)throw conflict("UNSAFE_STORED_VALUE");return v;}
    private ObjectNode item(int ordinal,Long record,long user,String userName,long book,String title,Long copy,String barcode,String beforeRecord,String afterRecord,String beforeCopy,String afterCopy,Long beforeVersion,Long afterVersion,String borrowDate,String dueDate,String dueKind,String returnDate,int renew) {
        return obj("ordinal",ordinal,"recordId",record,"userId",safe(user),"userName",userName,"bookId",safe(book),"bookTitle",title,"copyId",copy,"barcode",barcode,"beforeRecordStatus",beforeRecord,"afterRecordStatus",afterRecord,"beforeCopyStatus",beforeCopy,"afterCopyStatus",afterCopy,"copyVersionBefore",beforeVersion,"copyVersionAfter",afterVersion,"borrowDate",borrowDate,"dueDate",dueDate,"dueDateKind",dueKind,"returnDate",returnDate,"renewCount",renew);
    }
    private String display(long book,Map<String,Object> b){var rows=jdbc.queryForList("SELECT display_title_override FROM catalog_book_attribute WHERE book_id=?",book);return rows.isEmpty()||rows.get(0).get("display_title_override")==null?str(b,"title"):str(rows.get(0),"display_title_override");}

    public JsonNode orders(Map<String,String[]> query,long actor) {
        authorize(actor);Set<String> allowed=Set.of("page","size","userId","state");query.forEach((k,v)->{if(!allowed.contains(k)||v==null||v.length!=1)invalid();});
        long page=queryNumber(q(query,"page","1")),size=queryNumber(q(query,"size","20"));if(page>Integer.MAX_VALUE||size>100)invalid();
        Long user=query.containsKey("userId")?queryNumber(q(query,"userId",null)):null;String state=q(query,"state",null);if(state!=null&&!Set.of("BORROWING","PARTIALLY_RETURNED","RETURNED").contains(state))invalid();
        return tx(true,()->{
            authorize(actor);List<Object> args=new ArrayList<>();String where=" WHERE 1=1";
            if(user!=null){where+=" AND o.user_id=?";args.add(user);}if(state!=null){where+=" AND "+stateSql()+"=?";args.add(state);}
            long total=jdbc.queryForObject("SELECT COUNT(*) FROM circulation_order o"+where,Long.class,args.toArray());args.add(size);args.add((page-1)*size);
            ArrayNode records=JSON.createArrayNode();for(var r:jdbc.queryForList(orderSelect()+where+" ORDER BY o.id DESC LIMIT ? OFFSET ?",args.toArray()))records.add(orderSummary(r));
            return obj("schemaVersion",1,"records",records,"total",total,"page",page,"size",size,"pages",(total+size-1)/size);
        });
    }
    public JsonNode order(long id,long actor) {
        authorize(actor);safe(id);return tx(true,()->{
            authorize(actor);var r=one(orderSelect()+" WHERE o.id=?","ORDER_NOT_FOUND",id);ObjectNode out=orderSummary(r);out.put("schemaVersion",1);ArrayNode items=JSON.createArrayNode();
            for(var i:jdbc.queryForList("SELECT i.*,r.status,r.borrow_date,r.due_date,r.due_date_kind,r.return_date,r.renew_count FROM circulation_order_item i JOIN borrow_record r ON r.id=i.record_id WHERE i.order_id=? ORDER BY i.ordinal",id)) {
                items.add(obj("ordinal",num(i,"ordinal"),"recordId",safe(num(i,"record_id")),"bookId",safe(num(i,"book_id")),"bookTitle",i.get("book_title"),"copyId",safe(num(i,"copy_id")),"barcode",i.get("barcode"),"status",i.get("status"),"borrowDate",date(i.get("borrow_date")),"dueDate",date(i.get("due_date")),"dueDateKind",i.get("due_date_kind"),"returnDate",date(i.get("return_date")),"renewCount",i.get("renew_count")));
            }out.set("items",items);return out;
        });
    }
    private static final String ACTIVE="(SELECT COUNT(*) FROM circulation_order_item i JOIN borrow_record r ON r.id=i.record_id WHERE i.order_id=o.id AND r.deleted=0 AND r.status IN ('BORROWING','OVERDUE'))";
    private static final String RETURNED="(SELECT COUNT(*) FROM circulation_order_item i JOIN borrow_record r ON r.id=i.record_id WHERE i.order_id=o.id AND r.status='RETURNED')";
    private String stateSql(){return "(CASE WHEN "+RETURNED+"=o.item_count THEN 'RETURNED' WHEN "+RETURNED+">0 THEN 'PARTIALLY_RETURNED' ELSE 'BORROWING' END)";}
    private String orderSelect(){return "SELECT o.*,u.real_name AS user_name,p.card_no,"+ACTIVE+" AS active_count,"+RETURNED+" AS returned_count,"+stateSql()+" AS order_state FROM circulation_order o LEFT JOIN sys_user u ON u.id=o.user_id LEFT JOIN member_profile p ON p.user_id=o.user_id";}
    private ObjectNode orderSummary(Map<String,Object> r){return obj("orderId",safe(num(r,"id")),"userId",safe(num(r,"user_id")),"userName",r.get("user_name"),"cardNo",r.get("card_no"),"actorId",safe(num(r,"actor_id")),"createdAt",utc(r.get("created_at")),"businessDate",date(r.get("business_date")),"itemCount",num(r,"item_count"),"activeCount",num(r,"active_count"),"returnedCount",num(r,"returned_count"),"state",r.get("order_state"),"remark",r.get("remark"));}
    private String q(Map<String,String[]> q,String key,String fallback){return q.containsKey(key)?q.get(key)[0]:fallback;}
    private Map<String,Object> one(String sql,String missing,Object... args){var rows=jdbc.queryForList(sql,args);if(rows.isEmpty())throw fail(404,missing);return rows.get(0);}
    private long insert(String sql,Object... args){GeneratedKeyHolder keys=new GeneratedKeyHolder();jdbc.update(c->{var p=c.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},keys);return safe(Objects.requireNonNull(keys.getKey()).longValue());}
    private void changed(int count){if(count!=1)throw fail(500,"CIRCULATION_SAVE_FAILED");}
    private static long num(Map<String,Object> r,String key){Object v=r.get(key);return v instanceof Number n?n.longValue():v instanceof Boolean b?(b?1:0):Long.parseLong(v.toString());}
    private static String str(Map<String,Object> r,String key){Object v=r.get(key);return v==null?null:v.toString();}
    private static String date(Object v){return v==null?null:v.toString();}
    private static JsonNode json(Object v){return v==null?null:read(v.toString());}
    private static Long nullable(JsonNode n){return n==null||n.isNull()?null:n.longValue();}
}
