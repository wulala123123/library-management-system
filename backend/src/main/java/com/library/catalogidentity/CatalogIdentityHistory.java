package com.library.catalogidentity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

import static com.library.catalogattributes.CatalogAttributeSupport.*;
import static com.library.catalogidentity.CatalogIdentitySupport.normalized;

/** All identity writers join the caller transaction and take the catalog mutex first. */
public final class CatalogIdentityHistory {
    private CatalogIdentityHistory() {}

    public static void lock(JdbcTemplate j) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw fail(500,"CATALOG_IDENTITY_TRANSACTION_REQUIRED");
        j.queryForObject("SELECT id FROM inventory_mutex WHERE id=1 FOR UPDATE",Long.class);
    }

    public static ObjectNode owner(JdbcTemplate j,String kind,long id,boolean lock) {
        String table=kind.equals("PUBLISHER")?"publisher":"book_category";
        String columns=kind.equals("PUBLISHER")?"id,name,status,deleted,catalog_identity_revision":"id,name,parent_id,level,sort_order,status,deleted,catalog_identity_revision";
        List<Map<String,Object>> rows=j.queryForList("SELECT "+columns+" FROM "+table+" WHERE id=?"+(lock?" FOR UPDATE":""),id);
        if(rows.isEmpty())return null;
        Map<String,Object> r=rows.get(0);
        ObjectNode value=obj("id",id,"name",r.get("name"),"status",r.get("status"),"deleted",r.get("deleted"),"revision",num(r.get("catalog_identity_revision")));
        if(!kind.equals("PUBLISHER")){value.set("parentId",tree(r.get("parent_id")));value.set("level",tree(r.get("level")));value.set("sortOrder",tree(r.get("sort_order")));}
        return value;
    }

    public static ObjectNode book(JdbcTemplate j,long id) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw fail(500,"CATALOG_IDENTITY_TRANSACTION_REQUIRED");
        List<Map<String,Object>> rows=j.queryForList("SELECT id,title,publisher,category_id,deleted,catalog_identity_revision FROM book WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty())return null;Map<String,Object> r=rows.get(0);
        return obj("bookId",id,"title",r.get("title"),"publisherRaw",r.get("publisher"),"categoryId",r.get("category_id"),"deleted",r.get("deleted"),"revision",num(r.get("catalog_identity_revision")));
    }

    public static void recordOwner(JdbcTemplate j,String kind,long id,ObjectNode before,String reason) {
        ObjectNode current=owner(j,kind,id,true);if(current==null)return;
        if(before!=null && hash(before).equals(hash(current)))return;
        long old=before==null?0:before.path("revision").longValue();if(old==Long.MAX_VALUE)throw conflict("CATALOG_IDENTITY_CHANGED");
        long next=old+1;String table=kind.equals("PUBLISHER")?"publisher":"book_category";
        j.update("UPDATE "+table+" SET catalog_identity_revision=? WHERE id=?",next,id);current.put("revision",next);
        String profile=kind.equals("PUBLISHER")?"catalog_publisher_profile":"catalog_category_profile",column=kind.equals("PUBLISHER")?"publisher_id":"category_id";
        String name=current.path("name").textValue();
        j.update("UPDATE "+profile+" SET normalized_name=?,normalized_key=?,revision=revision+1,updated_at=? WHERE "+column+"=?",normalized(name),bytes(normalized(name)),local(now()),id);
        long actor=actor(SecurityContextHolder.getContext().getAuthentication()).id();
        event(j,null,null,kind,id,old,next,List.of("legacyIdentity"),before,current,null,actor,reason);
    }

    public static void recordBook(JdbcTemplate j,long id,ObjectNode before,String reason) {
        ObjectNode current=book(j,id);if(current==null||before==null||hash(current).equals(hash(before)))return;
        long old=before.path("revision").longValue();if(old==Long.MAX_VALUE)throw conflict("CATALOG_IDENTITY_CHANGED");
        j.update("UPDATE book SET catalog_identity_revision=? WHERE id=?",old+1,id);current.put("revision",old+1);
        long actor=actor(SecurityContextHolder.getContext().getAuthentication()).id();
        event(j,null,id,"BOOK_METADATA",id,old,old+1,List.of("legacyBookIdentity"),before,current,null,actor,reason);
    }

    public static long event(JdbcTemplate j,Long request,Long book,String kind,Long entity,long old,long next,Object fields,JsonNode before,JsonNode after,JsonNode evidence,long actor,String reason){
        return insert(j,"INSERT INTO catalog_identity_event(request_id,book_id,entity_kind,entity_id,before_revision,after_revision,changed_fields_json,before_json,after_json,evidence_json,actor_id,occurred_at,reason) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                request,book,kind,entity,old,next,write(fields),write(before),write(after),evidence==null?null:write(evidence),actor,local(now()),reason);
    }

    public static void ownerDeletionGuard(JdbcTemplate j,String kind,long id) {
        String links=kind.equals("PUBLISHER")?"catalog_book_publisher":"catalog_book_theme",column=kind.equals("PUBLISHER")?"publisher_id":"category_id",aliases=kind.equals("PUBLISHER")?"catalog_publisher_alias":"catalog_category_alias";
        if(j.queryForObject("SELECT COUNT(*) FROM "+links+" WHERE "+column+"=?",Long.class,id)>0 ||
                j.queryForObject("SELECT COUNT(*) FROM "+aliases+" WHERE "+column+"=?",Long.class,id)>0 ||
                j.queryForObject("SELECT COUNT(*) FROM catalog_identity_event WHERE entity_kind=? AND entity_id=?",Long.class,kind,id)>0 ||
                j.queryForObject("SELECT COUNT(*) FROM catalog_identity_candidate WHERE entity_kind=? AND candidate_id=?",Long.class,kind,id)>0)
            throw conflict("CATALOG_IDENTITY_HISTORY_REFERENCED");
    }

    public static void bookDeletionGuard(JdbcTemplate j,long id) {
        if(j.queryForObject("SELECT COUNT(*) FROM catalog_book_publisher WHERE book_id=?",Long.class,id)>0 ||
           j.queryForObject("SELECT COUNT(*) FROM catalog_book_theme WHERE book_id=?",Long.class,id)>0 ||
           j.queryForObject("SELECT COUNT(*) FROM catalog_identity_event WHERE book_id=?",Long.class,id)>0 ||
           j.queryForObject("SELECT COUNT(*) FROM catalog_identity_request WHERE book_id=? AND (state='COMMITTED' OR expires_at>?)",Long.class,id,local(now()))>0)
            throw conflict("CATALOG_IDENTITY_HISTORY_REFERENCED");
    }
}
