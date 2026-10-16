package com.library.catalogattributes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static com.library.catalogattributes.CatalogAttributeSupport.*;

/** Joins the caller's catalog -> book transaction; never opens an audit transaction. */
public final class CatalogAttributeHistory {
    private CatalogAttributeHistory() {}
    public static ObjectNode profile(JdbcTemplate jdbc,long bookId,String title,boolean lock) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM catalog_book_attribute WHERE book_id=?"+(lock?" FOR UPDATE":""),bookId);
        ObjectNode p=obj("bookId",bookId,"revision",0,"catalogTitle",title,"displayTitleOverride",null,"displayOrigin","UNKNOWN",
                "adoptedSourceTitle",null,"bindingCode",null,"bindingOrigin","UNKNOWN","ageLabels",null,"ageOrigin","UNKNOWN");
        if(rows.isEmpty())return p;
        Map<String,Object> r=rows.get(0);
        p.put("revision",num(r.get("revision")));p.set("displayTitleOverride",tree(r.get("display_title_override")));p.set("displayOrigin",tree(r.get("display_origin")));
        p.set("adoptedSourceTitle",tree(r.get("adopted_source_title")));p.set("bindingCode",tree(r.get("binding_code")));p.set("bindingOrigin",tree(r.get("binding_origin")));p.set("ageOrigin",tree(r.get("age_origin")));
        List<String> labels=jdbc.queryForList("SELECT label_code FROM catalog_book_age_label WHERE book_id=? ORDER BY CASE label_code WHEN 'AGE_0_2' THEN 1 WHEN 'AGE_3_4' THEN 2 WHEN 'AGE_5_6' THEN 3 WHEN 'AGE_7_9' THEN 4 ELSE 5 END"+(lock?" FOR UPDATE":""),String.class,bookId);
        if(num(r.get("age_known"))==1){if(labels.isEmpty()||labels.size()>5)throw fail(500,"CATALOG_SAVE_FAILED");p.set("ageLabels",tree(labels));}
        else if(!labels.isEmpty())throw fail(500,"CATALOG_SAVE_FAILED");
        return p;
    }
    public static String catalogDigest(JsonNode p){return hash(obj("schemaVersion",1,"profile",p));}
    public static String displayTitle(JsonNode p){return p.get("displayTitleOverride").isNull()?p.path("catalogTitle").asText():p.path("displayTitleOverride").asText();}
    public static void save(JdbcTemplate j,JsonNode p,boolean labels,String at){
        Object[] values={p.path("revision").longValue(),str(p,"displayTitleOverride"),str(p,"displayOrigin"),str(p,"adoptedSourceTitle"),str(p,"bindingCode"),str(p,"bindingOrigin"),p.get("ageLabels").isNull()?0:1,str(p,"ageOrigin"),local(at),p.path("bookId").longValue()};
        int n=j.update("UPDATE catalog_book_attribute SET revision=?,display_title_override=?,display_origin=?,adopted_source_title=?,binding_code=?,binding_origin=?,age_known=?,age_origin=?,updated_at=? WHERE book_id=?",values);
        if(n==0)j.update("INSERT INTO catalog_book_attribute(revision,display_title_override,display_origin,adopted_source_title,binding_code,binding_origin,age_known,age_origin,updated_at,book_id) VALUES (?,?,?,?,?,?,?,?,?,?)",values);
        if(labels){j.update("DELETE FROM catalog_book_age_label WHERE book_id=?",p.path("bookId").longValue());if(!p.get("ageLabels").isNull())for(JsonNode label:p.get("ageLabels"))j.update("INSERT INTO catalog_book_age_label(book_id,label_code) VALUES (?,?)",p.path("bookId").longValue(),label.textValue());}
    }
    private static String str(JsonNode n,String k){return n.get(k).isNull()?null:n.get(k).textValue();}
    public static long event(JdbcTemplate j,Long requestId,String kind,JsonNode before,JsonNode after,List<String> fields,JsonNode source,long actorId,String at,String reason){
        return insert(j,"INSERT INTO catalog_attribute_event(book_id,request_id,kind,before_revision,after_revision,schema_version,changed_fields_json,before_json,after_json,source_evidence_json,actor_id,occurred_at,reason) VALUES (?,?,?,?,?,1,?,?,?,?,?,?,?)",
            before.path("bookId").longValue(),requestId,kind,before.path("revision").longValue(),after.path("revision").longValue(),write(fields),write(before),write(after),source==null?null:write(source),actorId,local(at),reason);
    }
    public static void recordLegacyTitleChange(JdbcTemplate original,long bookId,String oldTitle,String newTitle,String reason){
        if(Objects.equals(oldTitle,newTitle))return;
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw fail(500,"CATALOG_SAVE_FAILED");
        Actor actor=actor(SecurityContextHolder.getContext().getAuthentication());actor.role();
        JdbcTemplate j=new CatalogAttributeJdbc(Objects.requireNonNull(original.getDataSource()));
        ObjectNode before=profile(j,bookId,oldTitle,true);
        if(before.path("revision").longValue()==Long.MAX_VALUE)throw conflict("REVISION_CONFLICT");
        ObjectNode after=before.deepCopy().put("catalogTitle",newTitle).put("revision",before.path("revision").longValue()+1);
        String at=now();save(j,after,false,at);event(j,null,"LEGACY_TITLE",before,after,List.of("catalogTitle"),null,actor.id(),at,reason);
    }
    public static void deletionGuard(JdbcTemplate j,long bookId){
        if(j.queryForObject("SELECT COUNT(*) FROM catalog_book_attribute WHERE book_id=?",Long.class,bookId)>0||
           j.queryForObject("SELECT COUNT(*) FROM catalog_attribute_event WHERE book_id=?",Long.class,bookId)>0||
           j.queryForObject("SELECT COUNT(*) FROM catalog_attribute_request WHERE state='PREVIEWED' AND expires_at>? AND JSON_EXTRACT(request_json,'$.bookId')=?",Long.class,local(now()),bookId)>0)
            throw conflict("CATALOG_HISTORY_REFERENCED");
    }
}
