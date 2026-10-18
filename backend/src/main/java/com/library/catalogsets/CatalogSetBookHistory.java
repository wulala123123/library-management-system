package com.library.catalogsets;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.library.catalogattributes.CatalogAttributeSupport.*;
import static com.library.catalogsets.CatalogSetSupport.*;

/** Only exact ISBN/title/deleted transitions advance this independent guard. */
public final class CatalogSetBookHistory {
    private CatalogSetBookHistory() {}
    public static ObjectNode snapshot(JdbcTemplate j,long id,boolean lock){if(lock&&!TransactionSynchronizationManager.isActualTransactionActive())throw failure(500,"CATALOG_SET_SAVE_FAILED");var rows=j.queryForList("SELECT id,isbn,title,deleted,catalog_set_book_revision FROM book WHERE id=?"+(lock?" FOR UPDATE":""),id);if(rows.isEmpty())return null;var r=rows.get(0);return obj("isbn",r.get("isbn"),"title",r.get("title"),"deleted",r.get("deleted"),"revision",r.get("catalog_set_book_revision"));}
    public static void recordIfChanged(JdbcTemplate j,long id,ObjectNode before){if(!TransactionSynchronizationManager.isActualTransactionActive())throw failure(500,"CATALOG_SET_SAVE_FAILED");ObjectNode after=snapshot(j,id,true);if(before==null||after==null)throw failure(409,"BOOK_IDENTITY_CHANGED");if(before.get("isbn").equals(after.get("isbn"))&&before.get("title").equals(after.get("title"))&&before.get("deleted").equals(after.get("deleted")))return;long old=before.path("revision").asLong();if(j.update("UPDATE book SET catalog_set_book_revision=?,update_time=update_time WHERE id=? AND catalog_set_book_revision=?",next(old),id,old)!=1)throw failure(409,"BOOK_IDENTITY_CHANGED");}
    public static void deletionGuard(JdbcTemplate j,long id){if(!TransactionSynchronizationManager.isActualTransactionActive())throw failure(500,"CATALOG_SET_SAVE_FAILED");if(!j.queryForList("SELECT id FROM catalog_set_member WHERE book_id=? LIMIT 1",id).isEmpty()||!j.queryForList("SELECT rb.request_id FROM catalog_set_request_book rb JOIN catalog_set_request r ON r.id=rb.request_id WHERE rb.book_id=? AND (r.state='COMMITTED' OR (r.state='PREVIEWED' AND r.expires_at>?)) LIMIT 1",id,local(now())).isEmpty())throw failure(409,"CATALOG_SET_REFERENCED");}
}
