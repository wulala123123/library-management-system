package com.library.catalogattributes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.library.catalogattributes.CatalogAttributeSupport.*;
import static com.library.catalogattributes.CatalogAttributeHistory.*;

@Service
public class CatalogAttributeService {
    final JdbcTemplate jdbc;
    final PlatformTransactionManager transactions;
    final CatalogAttributeSource sources;
    public CatalogAttributeService(JdbcTemplate jdbc,PlatformTransactionManager transactions){this.jdbc=new CatalogAttributeJdbc(Objects.requireNonNull(jdbc.getDataSource()));this.transactions=transactions;sources=new CatalogAttributeSource(this.jdbc);}
    <T>T tx(int isolation,boolean readOnly,Supplier<T> action){TransactionTemplate t=new TransactionTemplate(transactions);t.setIsolationLevel(isolation);t.setTimeout(15);t.setReadOnly(readOnly);try{return t.execute(s->action.get());}catch(QueryTimeoutException|PessimisticLockingFailureException e){throw conflict("CATALOG_BUSY");}catch(TransactionTimedOutException e){throw conflict("CATALOG_BUSY");}catch(DataAccessException e){throw fail(500,"CATALOG_SAVE_FAILED");}}
    Map<String,Object> book(long id,boolean lock){if(id<=0)invalid();List<Map<String,Object>> r=jdbc.queryForList("SELECT * FROM book WHERE id=? AND deleted=0"+(lock?" FOR UPDATE":""),id);if(r.isEmpty())throw fail(404,"BOOK_NOT_FOUND");return r.get(0);}
    ObjectNode view(long id,boolean lock){Map<String,Object>b=book(id,lock);ObjectNode p=profile(jdbc,id,(String)b.get("title"),lock);return obj("schemaVersion",1,"profile",p,"displayTitle",displayTitle(p),"catalogDigest",catalogDigest(p));}
    public JsonNode attributes(long id,Actor actor){actor.role();return tx(TransactionDefinition.ISOLATION_REPEATABLE_READ,true,()->view(id,false));}
    public JsonNode source(long id,Actor actor){actor.source();return tx(TransactionDefinition.ISOLATION_REPEATABLE_READ,true,()->sources.read(id,view(id,false).get("profile"),false));}

    ObjectNode validate(JsonNode n,Actor actor){
        actor.role();keys(n,"operationKey,mode,reason,bookId,expectedRevision,expectedCatalogDigest,sourceDigest,changes");
        key(n.get("operationKey"));String mode=word(n.get("mode"),List.of("MANUAL","SOURCE"));actor.mode(mode);text(n.get("reason"),500);number(n.get("bookId"),true);number(n.get("expectedRevision"),false);digest(n.get("expectedCatalogDigest"));
        if(mode.equals("MANUAL")){if(!n.get("sourceDigest").isNull())invalid();}else digest(n.get("sourceDigest"));
        JsonNode changes=n.get("changes");keys(changes,"displayTitleOverride,bindingCode,ageLabels,adoptedSourceTitle");boolean any=false;
        ObjectNode normalized=n.deepCopy();
        for(String field:FIELDS){JsonNode c=changes.get(field);if(c.isNull())continue;any=true;String action=word(c.get("action"),List.of("SET","CLEAR","ADOPT"));keys(c,action.equals("SET")?"action,value":"action");
            if(mode.equals("SOURCE")){if(!action.equals("ADOPT"))invalid();continue;}
            if(action.equals("ADOPT"))invalid();
            if(field.equals("adoptedSourceTitle")){actor.source();if(!action.equals("CLEAR"))invalid();}
            if(action.equals("SET")){switch(field){case "displayTitleOverride"->text(c.get("value"),200);case "bindingCode"->word(c.get("value"),BINDINGS);case "ageLabels"->((ObjectNode)normalized.get("changes").get(field)).set("value",ages(c.get("value")));default->invalid();}}
        }
        if(!any)invalid();return (ObjectNode)canonical(normalized);
    }
    private Map<String,Object> request(String key,Long id,boolean lock){List<Map<String,Object>> r=key!=null?jdbc.queryForList("SELECT * FROM catalog_attribute_request WHERE operation_key=?"+(lock?" FOR UPDATE":""),bytes(key)):jdbc.queryForList("SELECT * FROM catalog_attribute_request WHERE id=?"+(lock?" FOR UPDATE":""),id);return r.isEmpty()?null:r.get(0);}
    private void owned(Map<String,Object> r,Actor actor){actor.role();if(r==null)throw fail(404,"REQUEST_NOT_FOUND");actor.mode((String)r.get("mode"));if(num(r.get("actor_id"))!=actor.id())throw fail(403,"OWNER_REQUIRED");}
    private JsonNode requestView(Map<String,Object> r){return obj("requestId",num(r.get("id")),"operationKey",new String((byte[])r.get("operation_key"),java.nio.charset.StandardCharsets.UTF_8),"mode",r.get("mode"),"state",r.get("state"),"previewDigest",r.get("preview_digest"),"expiresAt",time(r.get("expires_at")),"preview",read((String)r.get("preview_json")),"receipt",r.get("receipt_json")==null?null:read((String)r.get("receipt_json")));}
    public JsonNode get(long id,Actor actor){actor.role();if(id<=0)invalid();return tx(TransactionDefinition.ISOLATION_REPEATABLE_READ,true,()->{var r=request(null,id,false);owned(r,actor);return requestView(r);});}
    public JsonNode byKey(String key,Actor actor){actor.role();key(tree(key));return tx(TransactionDefinition.ISOLATION_REPEATABLE_READ,true,()->{var r=request(key,null,false);owned(r,actor);return requestView(r);});}
    private String inputHash(JsonNode n,Actor actor){return hash(obj("actorId",actor.id(),"mode",n.get("mode"),"input",n));}
    private JsonNode duplicate(Map<String,Object> r,JsonNode n,Actor actor){if(num(r.get("actor_id"))!=actor.id()||!r.get("input_hash").equals(inputHash(n,actor)))throw conflict("IDEMPOTENCY_KEY_REUSED");actor.mode((String)r.get("mode"));return requestView(r);}
    public JsonNode preview(JsonNode input,Actor actor){
        ObjectNode n=validate(input,actor);
        // A uniqueness race must roll back its failed transaction before reading the winner.
        try{return previewTransaction(n,actor);}catch(DuplicatePreview race){return tx(TransactionDefinition.ISOLATION_REPEATABLE_READ,true,()->{var r=request(n.path("operationKey").asText(),null,false);if(r==null)throw conflict("CATALOG_BUSY");return duplicate(r,n,actor);});}
    }
    static final class DuplicatePreview extends RuntimeException {}
    private JsonNode previewTransaction(ObjectNode n,Actor actor){return tx(TransactionDefinition.ISOLATION_REPEATABLE_READ,false,()->{
        String key=n.path("operationKey").textValue(),mode=n.path("mode").textValue();var existing=request(key,null,false);if(existing!=null)return duplicate(existing,n,actor);
        long id=n.path("bookId").longValue();ObjectNode current=view(id,false);ObjectNode before=(ObjectNode)current.get("profile");expected(n,current);
        ObjectNode source=mode.equals("SOURCE")?sources.read(id,before,false):null;if(source!=null&&!source.path("sourceDigest").asText().equals(n.path("sourceDigest").asText()))throw conflict("SOURCE_CHANGED");
        Change change=change(before,n,source);String at=now(),expires=Instant.parse(at).plusSeconds(900).toString();
        ObjectNode preview=obj("schemaVersion",1,"mode",mode,"reason",n.get("reason"),"createdAt",at,"expiresAt",expires,"book",obj("bookId",id,"expectedRevision",before.get("revision"),"catalogDigest",current.get("catalogDigest"),"sourceDigest",source==null?null:source.get("sourceDigest"),"before",before,"after",change.after,"changedFields",change.fields,"willChange",change.changed,"sourceEvidence",source));
        long requestId;
        try{requestId=insert(jdbc,"INSERT INTO catalog_attribute_request(operation_key,actor_id,mode,input_hash,schema_version,request_json,preview_json,preview_digest,state,created_at,expires_at) VALUES (?,?,?,?,1,?,?,?,'PREVIEWED',?,?)",bytes(key),actor.id(),mode,inputHash(n,actor),write(n),write(preview),hash(preview),local(at),local(expires));}catch(DuplicateKeyException e){throw new DuplicatePreview();}
        return requestView(request(null,requestId,false));
    });}
    void expected(JsonNode n,JsonNode current){if(n.path("expectedRevision").longValue()!=current.path("profile").path("revision").longValue())throw conflict("REVISION_CONFLICT");if(!n.path("expectedCatalogDigest").textValue().equals(current.path("catalogDigest").textValue()))throw conflict("CATALOG_CHANGED");}
    record Change(ObjectNode after,List<String> fields,boolean changed){}
    Change change(ObjectNode before,JsonNode n,JsonNode source){if(before.path("revision").longValue()==Long.MAX_VALUE)throw conflict("REVISION_CONFLICT");ObjectNode after=before.deepCopy();List<String> fields=new ArrayList<>();boolean adopted=n.path("mode").asText().equals("SOURCE");
        for(String field:FIELDS){JsonNode c=n.get("changes").get(field);if(c.isNull())continue;JsonNode value;
            if(adopted){JsonNode f=source.path("fields").get(field);if(f==null||!f.path("canAdopt").asBoolean())throw conflict("SOURCE_NOT_ADOPTABLE");value=f.get("proposedValue");}
            else value=c.path("action").asText().equals("CLEAR")?NullNode.instance:c.get("value");
            String origin=switch(field){case "displayTitleOverride"->"displayOrigin";case "bindingCode"->"bindingOrigin";case "ageLabels"->"ageOrigin";default->null;};
            boolean different=adopted||!Objects.equals(after.get(field),value)||(origin!=null&&!after.path(origin).asText().equals("MANUAL"));
            after.set(field,value);if(origin!=null)after.put(origin,adopted?"SOURCE":"MANUAL");if(different)fields.add(field);
        }
        if(!fields.isEmpty()){if(before.path("revision").longValue()==Long.MAX_VALUE)throw conflict("REVISION_CONFLICT");after.put("revision",before.path("revision").longValue()+1);}
        return new Change(after,fields,!fields.isEmpty());
    }
    public JsonNode confirm(long id,JsonNode n,Actor actor){actor.role();if(id<=0)invalid();keys(n,"operationKey,previewDigest");key(n.get("operationKey"));digest(n.get("previewDigest"));return tx(TransactionDefinition.ISOLATION_READ_COMMITTED,false,()->{
        jdbc.queryForObject("SELECT id FROM inventory_mutex WHERE id=1 FOR UPDATE",Long.class);
        var r=request(null,id,true);owned(r,actor);if(!Arrays.equals((byte[])r.get("operation_key"),bytes(n.path("operationKey").textValue()))||!r.get("preview_digest").equals(n.path("previewDigest").textValue()))throw conflict("CATALOG_CHANGED");
        if(r.get("state").equals("COMMITTED"))return read((String)r.get("receipt_json"));
        if(!Instant.parse(time(r.get("expires_at"))).isAfter(Instant.now()))throw conflict("PREVIEW_EXPIRED");
        JsonNode input=read((String)r.get("request_json")),preview=read((String)r.get("preview_json"));long bookId=input.path("bookId").longValue();
        ObjectNode current=view(bookId,true);expected(input,current);ObjectNode before=(ObjectNode)current.get("profile");
        ObjectNode source=r.get("mode").equals("SOURCE")?sources.read(bookId,before,true):null;
        if(source!=null&&!source.path("sourceDigest").asText().equals(input.path("sourceDigest").asText()))throw conflict("SOURCE_CHANGED");
        Change change=change(before,input,source);if(!hash(change.after).equals(hash(preview.path("book").get("after")))||!tree(change.fields).equals(preview.path("book").get("changedFields")))throw conflict("CATALOG_CHANGED");
        String at=now();Long eventId=null;if(change.changed){save(jdbc,change.after,change.fields.contains("ageLabels"),at);eventId=event(jdbc,id,(String)r.get("mode"),before,change.after,change.fields,source==null?null:preview.path("book").get("sourceEvidence"),actor.id(),at,input.path("reason").textValue());}
        JsonNode receipt=obj("schemaVersion",1,"requestId",id,"operationKey",input.get("operationKey"),"mode",r.get("mode"),"actorId",actor.id(),"confirmedAt",at,"book",obj("bookId",bookId,"changed",change.changed,"beforeRevision",before.get("revision"),"afterRevision",change.after.get("revision"),"eventId",eventId,"profile",change.after,"displayTitle",displayTitle(change.after),"catalogDigest",catalogDigest(change.after)));
        if(jdbc.update("UPDATE catalog_attribute_request SET state='COMMITTED',receipt_json=?,committed_at=? WHERE id=? AND state='PREVIEWED'",write(receipt),local(at),id)!=1)throw conflict("CATALOG_CHANGED");return read(write(receipt));
    });}
    public JsonNode events(long bookId,int page,int size,Actor actor){actor.source();int p=Math.max(1,page),s=Math.min(100,Math.max(1,size));return tx(TransactionDefinition.ISOLATION_REPEATABLE_READ,true,()->{book(bookId,false);long total=jdbc.queryForObject("SELECT COUNT(*) FROM catalog_attribute_event WHERE book_id=?",Long.class,bookId);List<JsonNode> records=new ArrayList<>();for(var r:jdbc.queryForList("SELECT * FROM catalog_attribute_event WHERE book_id=? ORDER BY id DESC LIMIT ? OFFSET ?",bookId,s,(long)(p-1)*s))records.add(obj("eventId",num(r.get("id")),"bookId",bookId,"requestId",nullable(r.get("request_id")),"kind",r.get("kind"),"beforeRevision",num(r.get("before_revision")),"afterRevision",num(r.get("after_revision")),"changedFields",read((String)r.get("changed_fields_json")),"before",read((String)r.get("before_json")),"after",read((String)r.get("after_json")),"sourceEvidence",r.get("source_evidence_json")==null?null:read((String)r.get("source_evidence_json")),"actorId",num(r.get("actor_id")),"occurredAt",time(r.get("occurred_at")),"reason",r.get("reason")));return page(records,total,p,s);});}
    static JsonNode page(List<?> records,long total,int page,int size){return obj("records",records,"total",total,"page",page,"size",size,"pages",(total+size-1)/size);}
}
