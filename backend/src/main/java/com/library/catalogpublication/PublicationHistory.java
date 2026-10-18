package com.library.catalogpublication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.Clob;
import java.util.*;
import static com.library.catalogattributes.CatalogAttributeSupport.*;
import static com.library.catalogpublication.PublicationSupport.failure;

/** All publication snapshot writers join inventory_mutex -> ascending book locks. */
public final class PublicationHistory {
    private PublicationHistory() {}
    public static void lock(JdbcTemplate j){if(!TransactionSynchronizationManager.isActualTransactionActive())throw failure(500,"PUBLICATION_SAVE_FAILED");j.queryForObject("SELECT id FROM inventory_mutex WHERE id=1 FOR UPDATE",Long.class);}
    public static String string(Object v){if(v==null)return null;if(v instanceof Clob c)try{return c.getSubString(1,Math.toIntExact(c.length()));}catch(Exception e){throw failure(500,"PUBLICATION_SAVE_FAILED");}return v.toString();}
    public static ObjectNode snapshot(JdbcTemplate j,long id,boolean lock){var rows=j.queryForList("SELECT id,isbn,title,publish_date,pages,description,publication_revision,deleted FROM book WHERE id=?"+(lock?" FOR UPDATE":""),id);if(rows.isEmpty())return null;var r=rows.get(0);return obj("schemaVersion",1,"bookId",id,"isbn",r.get("isbn"),"catalogTitle",r.get("title"),"publishDate",string(r.get("publish_date")),"pages",r.get("pages"),"description",string(r.get("description")),"revision",num(r.get("publication_revision")),"deleted",num(r.get("deleted")));}
    public static ObjectNode publication(JdbcTemplate j,ObjectNode snapshot){ObjectNode n=snapshot.deepCopy();n.remove(List.of("schemaVersion","deleted"));var display=j.queryForList("SELECT display_title_override FROM catalog_book_attribute WHERE book_id=?",snapshot.path("bookId").asLong());n.put("displayTitle",!display.isEmpty()&&display.get(0).get("display_title_override")!=null?string(display.get(0).get("display_title_override")):snapshot.path("catalogTitle").asText());n.put("metadataDigest",hash(snapshot));return n;}
    public static ArrayNode fields(JsonNode before,JsonNode after){ArrayNode fields=JSON.createArrayNode();for(String field:List.of("isbn","catalogTitle","publishDate","pages","description","deleted"))if(!Objects.equals(before.get(field),after.get(field)))fields.add(field);return fields;}
    public static void legacy(JdbcTemplate j,long id,ObjectNode before,String kind){if(!TransactionSynchronizationManager.isActualTransactionActive())throw failure(500,"PUBLICATION_SAVE_FAILED");ObjectNode after=snapshot(j,id,true);if(before==null||after==null)return;ArrayNode fields=fields(before,after);if(fields.isEmpty())return;long old=before.path("revision").asLong();if(old==Long.MAX_VALUE)throw PublicationSupport.changed();if(j.update("UPDATE book SET publication_revision=?,update_time=update_time WHERE id=? AND publication_revision=?",old+1,id,old)!=1)throw PublicationSupport.changed();after.put("revision",old+1);var actor=PublicationSupport.actor(SecurityContextHolder.getContext().getAuthentication());actor.role();event(j,null,id,kind,before,after,fields,null,actor.id(),kind,now());}
    public static long event(JdbcTemplate j,Long request,long book,String kind,JsonNode before,JsonNode after,JsonNode fields,JsonNode evidence,long actor,String reason,String at){return insert(j,"INSERT INTO catalog_publication_event(book_id,request_id,kind,before_revision,after_revision,changed_fields_json,before_json,after_json,evidence_json,actor_id,occurred_at,reason) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",book,request,kind,before.path("revision").asLong(),after.path("revision").asLong(),write(fields),write(before),write(after),evidence==null?null:write(evidence),actor,local(at),reason);}
}
