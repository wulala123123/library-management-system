package com.library.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.library.dto.BorrowRequest;
import com.library.entity.*;
import com.library.mapper.*;
import com.library.security.JwtTokenProvider;
import com.library.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:copy-actions-acceptance;DB_CLOSE_DELAY=-1;MODE=MYSQL")
@AutoConfigureMockMvc @ActiveProfiles("test")
class BookCopyActionControllerTest {
    @Autowired protected MockMvc mvc;@Autowired protected JdbcTemplate jdbc;@Autowired protected ObjectMapper json;
    @Autowired protected BookMapper books;@Autowired protected BookCopyMapper copies;@Autowired protected JwtTokenProvider tokens;
    @Autowired protected BookCopyActionService actions;@Autowired protected BorrowRecordService borrows;@Autowired protected InventoryIntakeService intakes;@Autowired protected BookshelfService shelves;

    @Test void lifecyclePreservesAllExplainedDifferencesAndRestoresDisabledBeforeIndependentEnable()throws Exception{
        Book b=book(10,8);BookCopy target=copy(b,"AVAILABLE",null);
        for(int n=0;n<6;n++)copy(b,"AVAILABLE",null);BookCopy loan=copy(b,"BORROWED",null);active(b,loan,reader());
        JsonNode before=v2(b);assertEquals(2,before.path("inStockDifference").asLong());assertEquals(1,before.path("availableDifference").asLong());assertEquals(1,before.path("unexplainedStockBalanceDifference").asLong());
        String raw="javascript:文字 onload=原位 <script>纯文本</script> ";ObjectNode move=request("MOVE",target);location(move,"TEXT",raw);var business=counts();JsonNode preview=preview(move,"librarian");assertEquals(business,counts());
        JsonNode moved=confirm(move,preview,"librarian");assertTrue(moved.path("copies").get(0).get("stockFlowId").isNull());assertEquals(raw,copies.selectById(target.getId()).getLocation());assertNull(copies.selectById(target.getId()).getLastStateActionId());
        for(String action:List.of("DISABLE","WRITE_OFF","RESTORE","ENABLE")){
            ObjectNode r=request(action,target);if(action.equals("RESTORE"))restore(r,target,null);
            JsonNode receipt=confirm(r,preview(r,"admin"),"admin");BookCopy current=copies.selectById(target.getId());assertEquals(action.equals("ENABLE")?"AVAILABLE":action.equals("WRITE_OFF")?"WRITTEN_OFF":"DISABLED",current.getStatus());
            if(action.equals("RESTORE")){assertEquals(10,books.selectById(b.getId()).getStockTotal());assertEquals(7,books.selectById(b.getId()).getStockAvailable());assertNull(current.getLocation());}
            assertEquals(1,jdbc.queryForObject("SELECT quantity FROM stock_flow WHERE id=?",Integer.class,receipt.path("copies").get(0).path("stockFlowId").asLong()));
            JsonNode after=v2(b);for(String key:List.of("inStockDifference","availableDifference","borrowedDifference","unexplainedStockBalanceDifference"))assertEquals(before.path(key),after.path(key),key);
        }
        assertEquals(10,books.selectById(b.getId()).getStockTotal());assertEquals(8,books.selectById(b.getId()).getStockAvailable());assertEquals(5,copies.selectById(target.getId()).getActionVersion());
        JsonNode history=data(get("/book-copies/"+target.getId()+"/actions").param("size","2"),"librarian",200);assertEquals(5,history.path("total").asLong());assertEquals(2,history.path("records").size());
        assertEquals("ENABLE",history.path("records").get(0).path("action").asText());assertTrue(history.get("intakeSource").isNull());
        JsonNode restored=history.path("records").get(1);long source=restored.path("restoreOfActionId").asLong();assertTrue(source>0);
        JsonNode sourceEvent=data(get("/book-copies/"+target.getId()+"/actions").param("page","2").param("size","2"),"admin",200).path("records").get(0);assertEquals(source,sourceEvent.path("eventId").asLong());assertEquals(restored.path("eventId"),sourceEvent.path("restoredByActionId"));
    }
    @Test void canonicalStatusesNeedEvidenceAndUnknownOrInconsistentLoansNeverBecomeAvailable()throws Exception{
        Book b=book(10,10);for(String state:List.of("available","AVAILABLE ","borrowed","DISABLED","WRITTEN_OFF","disabled","原未知")){
            BookCopy c=copy(b,state,null);JsonNode context=data(get("/book-copies/"+c.getId()+"/action-context"),"admin",200);assertEquals("UNKNOWN",context.path("copy").path("stateClassification").asText());
            reason(post("/book-copy-actions/previews"),request("DISABLE",c),"admin",409,"UNKNOWN_LEGACY_STATUS");
        }
        BookCopy orphan=copy(b,"BORROWED",null);reason(post("/book-copy-actions/previews"),request("WRITE_OFF",orphan),"admin",409,"COPY_LINK_ANOMALY");
        BookCopy inconsistent=copy(b,"AVAILABLE",null);active(b,inconsistent,reader());reason(post("/book-copy-actions/previews"),request("DISABLE",inconsistent),"admin",409,"COPY_LINK_ANOMALY");
        BookCopy cross=copy(b,"BORROWED",null);active(book(1,0),cross,reader());reason(post("/book-copy-actions/previews"),request("MOVE",cross).set("unused",json.nullNode()),"admin",400,"INVALID_INPUT");
        ObjectNode move=request("MOVE",cross);location(move,"UNASSIGNED",null);reason(post("/book-copy-actions/previews"),move,"admin",409,"COPY_LINK_ANOMALY");
        BookCopy normal=copy(b,"BORROWED",null);active(b,normal,reader());reason(post("/book-copy-actions/previews"),request("DISABLE",normal),"admin",409,"COPY_ON_LOAN");
    }
    @Test void sourceMustBelongToThisCopyAndCurrentWriteOffAndCyclesRemainIndependent()throws Exception{
        Book b=book(2,2);BookCopy a=copy(b,"AVAILABLE",null),other=copy(b,"AVAILABLE",null);
        ObjectNode off=request("WRITE_OFF",a);JsonNode first=confirm(off,preview(off,"admin"),"admin");long event=first.path("copies").get(0).path("eventId").asLong();
        jdbc.update("UPDATE book_copy SET status='WRITTEN_OFF',last_state_action_id=?,action_version=1 WHERE id=?",event,other.getId());assertEquals("UNKNOWN",data(get("/book-copies/"+other.getId()+"/action-context"),"admin",200).path("copy").path("stateClassification").asText());
        ObjectNode invalid=request("RESTORE",a);restore(invalid,a,"找到");((ObjectNode)invalid.path("copies").get(0)).put("restoreOfActionId",event+999);reason(post("/book-copy-actions/previews"),invalid,"admin",409,"RESTORE_SOURCE_REQUIRED");
        for(int cycle=0;cycle<2;cycle++){
            ObjectNode restore=request("RESTORE",a);restore(restore,a,"找到位置");confirm(restore,preview(restore,"admin"),"admin");assertEquals("DISABLED",copies.selectById(a.getId()).getStatus());
            ObjectNode next=request("WRITE_OFF",a);confirm(next,preview(next,"admin"),"admin");
        }
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM book_copy_action WHERE copy_id=? AND action_type='RESTORE'",Integer.class,a.getId()));
        reason(post("/book-copy-actions/previews"),request("WRITE_OFF",a),"admin",409,"INVALID_TRANSITION");
    }
    @Test void idempotencyOwnerExpiryAndCommittedReplayPrecedeChangedCurrentState()throws Exception{
        Book b=book(1,1);BookCopy c=copy(b,"AVAILABLE","");ObjectNode r=request("DISABLE",c);JsonNode p=preview(r,"admin");assertEquals(p,preview(r,"admin"));
        reason(post("/book-copy-actions/previews"),r.deepCopy().put("reason","不同输入"),"admin",409,"IDEMPOTENCY_KEY_REUSED");
        reason(get("/book-copy-actions/"+p.path("requestId").asLong()),null,"librarian",403,"ACTION_OWNER_REQUIRED");reason(post(confirmPath(p)),confirmation(r,p),"librarian",403,"ACTION_OWNER_REQUIRED");
        JsonNode receipt=confirm(r,p,"admin");ObjectNode moved=request("MOVE",c);location(moved,"TEXT","当前位置");confirm(moved,preview(moved,"admin"),"admin");
        jdbc.update("UPDATE book_copy_action_request SET expires_at='2000-01-01' WHERE id=?",p.path("requestId").asLong());assertEquals(receipt,confirm(r,p,"admin"));
        assertEquals(receipt,data(get("/book-copy-actions/by-key").param("key",r.path("idempotencyKey").asText()),"admin",200).path("receipt"));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM book_copy_action WHERE copy_id=?",Integer.class,c.getId()));
        ObjectNode enable=request("ENABLE",c);JsonNode ep=preview(enable,"admin");jdbc.update("UPDATE book_copy_action_request SET expires_at='2000-01-01' WHERE id=?",ep.path("requestId").asLong());reason(post(confirmPath(ep)),confirmation(enable,ep),"admin",409,"PREVIEW_EXPIRED");
    }
    @Test void loanAndReturnIncrementVersionAndInvalidateAvailableAbaWithoutChangingLocationOrPointer()throws Exception{
        Book b=book(1,1);BookCopy c=copy(b,"AVAILABLE","原位置");ObjectNode r=request("DISABLE",c);JsonNode p=preview(r,"admin");
        BorrowRequest br=new BorrowRequest();br.setBookId(b.getId());br.setCopyId(c.getId());br.setUserId(reader());BorrowRecord loan=borrows.borrow(br);assertEquals(1,copies.selectById(c.getId()).getActionVersion());borrows.returnBook(loan.getId());
        assertEquals(2,copies.selectById(c.getId()).getActionVersion());assertEquals("原位置",copies.selectById(c.getId()).getLocation());assertNull(copies.selectById(c.getId()).getLastStateActionId());
        reason(post(confirmPath(p)),confirmation(r,p),"admin",409,"PREVIEW_STALE");assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM book_copy_action WHERE copy_id=?",Integer.class,c.getId()));
    }
    @Test void explicitHealthyCopyCanBeBorrowedIndependentlyButAutomaticUnknownAndAnomalousSelectionIsBlocked()throws Exception{
        Book b=book(3,3);BookCopy unknown=copy(b,"available",null),healthy=copy(b,"AVAILABLE",null);long user=reader();BorrowRequest br=new BorrowRequest();br.setBookId(b.getId());br.setUserId(user);assertThrows(RuntimeException.class,()->borrows.borrow(br));
        br.setCopyId(unknown.getId());assertThrows(RuntimeException.class,()->borrows.borrow(br));br.setCopyId(healthy.getId());BorrowRecord loan=borrows.borrow(br);assertEquals(healthy.getId(),loan.getCopyId());borrows.returnBook(loan.getId());
        BookCopy bad=copy(b,"AVAILABLE",null);active(b,bad,reader());br.setUserId(reader());br.setCopyId(bad.getId());assertThrows(RuntimeException.class,()->borrows.borrow(br));br.setCopyId(null);assertThrows(RuntimeException.class,()->borrows.borrow(br));
    }
    @Test void writtenOffRowsDoNotConsumeHistoricalFallbackAndNoFallbackInventsRegisteredAvailableCopies()throws Exception{
        Book b=book(2,2);BookCopy c=copy(b,"AVAILABLE",null);ObjectNode off=request("WRITE_OFF",c);confirm(off,preview(off,"admin"),"admin");
        assertEquals(1,books.selectById(b.getId()).getStockTotal());BorrowRequest br=new BorrowRequest();br.setBookId(b.getId());br.setUserId(reader());BorrowRecord legacy=borrows.borrow(br);assertNull(legacy.getCopyId());borrows.returnBook(legacy.getId());assertEquals(1,books.selectById(b.getId()).getStockAvailable());
        Book closed=book(1,1);BookCopy known=copy(closed,"AVAILABLE",null);ObjectNode disable=request("DISABLE",known);confirm(disable,preview(disable,"admin"),"admin");
        jdbc.update("UPDATE book SET stock_available=1 WHERE id=?",closed.getId());br.setBookId(closed.getId());br.setUserId(reader());assertThrows(RuntimeException.class,()->borrows.borrow(br));
    }
    @Test void metadataScopePositionsAndV2AppearInManagementSummaryAndCsvWithoutRepairingLegacyValues()throws Exception{
        Book b=book(1,1);BookCopy c=copy(b,"AVAILABLE","  ");ObjectNode off=request("WRITE_OFF",c);confirm(off,preview(off,"admin"),"admin");
        JsonNode summary=data(get("/book-copies/catalog-summary").param("bookId",b.getId().toString()),"admin",200).path("records").get(0);assertEquals(1,summary.path("registeredCopies").asLong());assertEquals(-1,summary.path("totalDifference").asLong());assertFalse(summary.path("needsVerification").asBoolean());assertEquals(0,summary.path("reconciliationV2").path("inStockDifference").asLong());
        assertEquals(1,data(get("/book-copies").param("bookId",b.getId().toString()).param("status","WRITTEN_OFF"),"admin",200).path("total").asLong());assertEquals(0,data(get("/book-copies").param("bookId",b.getId().toString()).param("status","UNKNOWN"),"admin",200).path("total").asLong());
        for(String path:List.of("/statistics","/inventory/stats","/dashboard/stats")){JsonNode stats=data(get(path),"admin",200);assertEquals("copy-lifecycle-v1",stats.path("reconciliationV2").path("schema").asText());assertTrue(stats.path("reconciliationV2").path("registeredWrittenOffCopies").asLong()>=1);}
        assertEquals("copy-lifecycle-v1",data(get("/inventory/reconciliation"),"admin",200).path("reconciliationV2").path("schema").asText());
        String csv=mvc.perform(get("/statistics/export").header("Authorization",bearer("admin"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);assertTrue(csv.contains("reconciliationV2.registeredWrittenOffCopies"));assertTrue(csv.contains("reconciliationV2.hasLoanLinkAnomaly"));
        jdbc.update("UPDATE book SET status=0 WHERE id=?",b.getId());ObjectNode restore=request("RESTORE",c);restore(restore,c,null);confirm(restore,preview(restore,"admin"),"admin");assertEquals(0,books.selectById(b.getId()).getStatus());assertEquals("DISABLED",copies.selectById(c.getId()).getStatus());
    }
    @Test void permissionsStrictFieldsAndTargetTypesRejectWithoutChangingAnyBusinessRows()throws Exception{
        Book b=book(1,1);BookCopy c=copy(b,"AVAILABLE",null);ObjectNode off=request("WRITE_OFF",c);reason(post("/book-copy-actions/previews"),off,"librarian",403,"ACTION_ROLE_REQUIRED");
        for(String path:List.of("/book-copies/"+c.getId()+"/action-context","/book-copies/"+c.getId()+"/actions"))reason(get(path),null,"reader",403,"ACTION_ROLE_REQUIRED");
        reason(post("/book-copy-actions/previews"),off,"reader",403,"ACTION_ROLE_REQUIRED");mvc.perform(get("/book-copies/"+c.getId()+"/actions")).andExpect(status().isUnauthorized());
        List<ObjectNode> invalid=new ArrayList<>();invalid.add(request("DISABLE",c).put("quantity",1));invalid.add(request("DISABLE",c).put("reason","bad\u0000"));
        ObjectNode numeric=request("DISABLE",c);((ObjectNode)numeric.path("copies").get(0)).put("copyId",c.getId().toString());invalid.add(numeric);
        ObjectNode duplicate=request("DISABLE",c);duplicate.withArray("copies").add(duplicate.path("copies").get(0).deepCopy());invalid.add(duplicate);
        ObjectNode extra=request("MOVE",c);location(extra,"REGISTERED","somewhere");invalid.add(extra);
        ObjectNode unknown=request("MOVE",c);location(unknown,"TEXT","a");((ObjectNode)unknown.path("copies").get(0).path("targetLocation")).put("locationId",7);invalid.add(unknown);
        for(String text:List.of("","   ","A\nB","x".repeat(201),"\uD800")){ObjectNode move=request("MOVE",c);location(move,"TEXT",text);invalid.add(move);}
        ObjectNode restore=request("RESTORE",c);restore(restore,c,null);((ObjectNode)restore.path("copies").get(0)).put("restoreTo","AVAILABLE");invalid.add(restore);
        var before=counts();for(ObjectNode r:invalid)reason(post("/book-copy-actions/previews"),r,"admin",400,"INVALID_INPUT");assertEquals(before,counts());
        JsonNode p=preview(request("DISABLE",c),"admin");
        assertTrue(p.get("receipt").isNull());reason(get("/book-copy-actions/999999999"),null,"admin",404,"REQUEST_NOT_FOUND");
    }
    @Test void movePreservesEveryBookColumnAndOldShelfBindingWhileOldAssignmentAndClearPreserveCopy()throws Exception{
        Book b=book(1,1);BookCopy c=copy(b,"AVAILABLE","原册位置");String code=unique();jdbc.update("INSERT INTO book_location(storey_id,code) VALUES (1,?)",code);long locationId=jdbc.queryForObject("SELECT id FROM book_location WHERE code=?",Long.class,code);
        var originalCopy=snapshot("book_copy",c.getId());shelves.assignBookToLocation(locationId,b.getId());assertEquals(originalCopy,snapshot("book_copy",c.getId()));
        jdbc.update("UPDATE book SET location='旧书目位置',update_time='2001-01-01 00:00:00' WHERE id=?",b.getId());var bookBefore=snapshot("book",b.getId());var bindingBefore=snapshot("book_location",locationId);
        ObjectNode move=request("MOVE",c);location(move,"TEXT","现在册位置");confirm(move,preview(move,"librarian"),"librarian");assertEquals(bookBefore,snapshot("book",b.getId()));assertEquals(bindingBefore,snapshot("book_location",locationId));
        var movedCopy=snapshot("book_copy",c.getId());shelves.clearLocation(locationId);assertEquals("EMPTY",jdbc.queryForObject("SELECT status FROM book_location WHERE id=?",String.class,locationId));assertEquals(movedCopy,snapshot("book_copy",c.getId()));
    }
    @Test void contextRejectsInventoryAndVersionOverflowWithTheSameReasonAsPreview()throws Exception{
        Book b=book(0,0);BookCopy c=copy(b,"AVAILABLE",null);JsonNode context=data(get("/book-copies/"+c.getId()+"/action-context"),"admin",200);JsonNode disable=permission(context,"DISABLE");assertFalse(disable.path("allowed").asBoolean());assertEquals("STOCK_CONSTRAINT",disable.path("reasonCode").asText());reason(post("/book-copy-actions/previews"),request("DISABLE",c),"admin",409,"STOCK_CONSTRAINT");
        jdbc.update("UPDATE book SET stock_total=1,stock_available=1 WHERE id=?",b.getId());jdbc.update("UPDATE book_copy SET action_version=? WHERE id=?",Long.MAX_VALUE,c.getId());context=data(get("/book-copies/"+c.getId()+"/action-context"),"admin",200);for(String action:List.of("MOVE","DISABLE","WRITE_OFF")){assertFalse(permission(context,action).path("allowed").asBoolean());assertEquals("STOCK_CONSTRAINT",permission(context,action).path("reasonCode").asText());}
        reason(post("/book-copy-actions/previews"),request("DISABLE",c),"admin",409,"STOCK_CONSTRAINT");
    }
    @Test void currentRoleDowngradeBlocksBothPendingConfirmationAndCommittedReplay()throws Exception{
        long operator=reader();String name=jdbc.queryForObject("SELECT username FROM sys_user WHERE id=?",String.class,operator);jdbc.update("UPDATE sys_user SET role='ADMIN' WHERE id=?",operator);Book b=book(2,2);BookCopy a=copy(b,"AVAILABLE",null),c=copy(b,"AVAILABLE",null);ObjectNode done=request("WRITE_OFF",a),pending=request("WRITE_OFF",c);JsonNode dp=preview(done,name),pp=preview(pending,name);confirm(done,dp,name);String originalToken=bearer(name);var before=counts();
        jdbc.update("UPDATE sys_user SET role='LIBRARIAN' WHERE id=?",operator);for(var pair:List.of(Map.entry(done,dp),Map.entry(pending,pp))){JsonNode result=response(post(confirmPath(pair.getValue())).header("Authorization",originalToken),confirmation(pair.getKey(),pair.getValue()),null,403);assertEquals("ACTION_ROLE_REQUIRED",result.path("data").path("reasonCode").asText());for(String field:List.of("requestId","copyId","ordinal"))assertTrue(result.path("data").has(field));}assertEquals(before,counts());assertEquals("AVAILABLE",copies.selectById(c.getId()).getStatus());
    }
    protected JsonNode snapshot(String table,long id){return json.valueToTree(jdbc.queryForMap("SELECT * FROM "+table+" WHERE id=?",id));}
    protected JsonNode permission(JsonNode context,String action){for(JsonNode item:context.path("actions"))if(item.path("action").asText().equals(action))return item;throw new AssertionError("Missing action: "+action);}
    @Test void wholeBatchAndAuditFailureRollBackCopiesStockFlowsEventsAndReceipt()throws Exception{
        Book b=book(2,2);BookCopy a=copy(b,"AVAILABLE",null),c=copy(b,"AVAILABLE",null);ObjectNode r=request("DISABLE",a,c);JsonNode p=preview(r,"admin");var before=counts();
        installFailure();try{reason(post(confirmPath(p)),confirmation(r,p),"admin",500,"ACTION_SAVE_FAILED");}finally{jdbc.execute("DROP TRIGGER copy_action_fail");}
        assertEquals(before,counts());assertEquals("AVAILABLE",copies.selectById(a.getId()).getStatus());assertEquals(0,copies.selectById(c.getId()).getActionVersion());assertEquals(p,data(get("/book-copy-actions/"+p.path("requestId").asLong()),"admin",200));
        confirm(r,p,"admin");assertEquals(0,books.selectById(b.getId()).getStockAvailable());
        Book broken=book(0,0);BookCopy invalid=copy(broken,"AVAILABLE",null);reason(post("/book-copy-actions/previews"),request("DISABLE",invalid),"admin",409,"STOCK_CONSTRAINT");
        ObjectNode batch=request("ENABLE",a,invalid);var current=counts();reason(post("/book-copy-actions/previews"),batch,"admin",409,"INVALID_TRANSITION");assertEquals(current,counts());
    }
    @Test void batchLimitKeepsRawUnicodeBodyAndVersionAndStockOverflowFailBeforeAnyWrite()throws Exception{
        Book b=book(100,100);BookCopy[] selected=new BookCopy[100];for(int n=0;n<100;n++)selected[n]=copy(b,"AVAILABLE",null);
        ObjectNode oversized=request("DISABLE",selected);oversized.withArray("copies").add(oversized.path("copies").get(0).deepCopy());reason(post("/book-copy-actions/previews"),oversized,"admin",400,"INVALID_INPUT");
        ObjectNode move=request("MOVE",selected).put("reason","原文javascript:onload= "+"绘".repeat(450));for(JsonNode input:move.path("copies"))((ObjectNode)input).set("targetLocation",json.createObjectNode().put("kind","TEXT").put("text","实际位置javascript:onload= "+"😀".repeat(160)));
        JsonNode moved=confirm(move,preview(move,"admin"),"admin");assertEquals(100,moved.path("copies").size());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM stock_flow WHERE book_id=?",Integer.class,b.getId()));
        ObjectNode off=request("WRITE_OFF",selected);JsonNode receipt=confirm(off,preview(off,"admin"),"admin");assertEquals(100,receipt.path("copies").size());assertEquals(0,books.selectById(b.getId()).getStockTotal());assertEquals(100,v2(b).path("registeredWrittenOffCopies").asLong());assertFalse(v2(b).path("hasHistoricalDifference").asBoolean());
        Book boundary=book(Integer.MAX_VALUE,1);BookCopy c=copy(boundary,"AVAILABLE",null);ObjectNode written=request("WRITE_OFF",c);confirm(written,preview(written,"admin"),"admin");jdbc.update("UPDATE book SET stock_total=? WHERE id=?",Integer.MAX_VALUE,boundary.getId());
        ObjectNode restore=request("RESTORE",c);restore(restore,c,null);var before=counts();reason(post("/book-copy-actions/previews"),restore,"admin",409,"STOCK_CONSTRAINT");assertEquals(before,counts());
        Book maxVersion=book(1,1);BookCopy version=copy(maxVersion,"AVAILABLE",null);jdbc.update("UPDATE book_copy SET action_version=? WHERE id=?",Long.MAX_VALUE,version.getId());reason(post("/book-copy-actions/previews"),request("DISABLE",version),"admin",409,"STOCK_CONSTRAINT");
    }
    @Test void v8PlanAndReceiptJsonStayUnmodifiedWhileViewAddsCurrentV2AndHistoryKeepsIntakeSource()throws Exception{
        Book b=book(0,0);ObjectNode r=json.createObjectNode().put("idempotencyKey",UUID.randomUUID().toString()).put("reason","V8格式合成来源");r.set("source",json.createObjectNode().put("type","MANUAL"));ObjectNode line=json.createObjectNode().put("lineKey",unique());line.set("target",json.createObjectNode().put("bookId",b.getId()));line.putArray("copies").add(json.createObjectNode().put("input","GENERATE"));r.putArray("lines").add(line);
        JsonNode preview=response(post("/inventory-intakes/previews"),r,"admin",200).path("data");ObjectNode confirmation=json.createObjectNode().put("idempotencyKey",r.path("idempotencyKey").asText()).put("previewDigest",preview.path("previewDigest").asText());
        long intake=preview.path("intakeId").asLong();JsonNode receipt=response(post("/inventory-intakes/"+intake+"/confirm"),confirmation,"admin",200).path("data");long copyId=receipt.path("copies").get(0).path("copyId").asLong();
        List<String> original=jdbc.queryForObject("SELECT request_json,preview_json,receipt_json FROM inventory_intake_request WHERE id=?",(rs,n)->List.of(rs.getString(1),rs.getString(2),rs.getString(3)),intake);
        assertFalse(json.readTree(original.get(1)).has("reconciliationV2ByBook"));assertFalse(json.readTree(original.get(2)).has("reconciliationV2ByBook"));
        BookCopy c=copies.selectById(copyId);ObjectNode off=request("WRITE_OFF",c);confirm(off,preview(off,"admin"),"admin");
        JsonNode view=data(get("/inventory-intakes/"+intake),"admin",200);assertEquals(1,view.path("reconciliationV2ByBook").get(0).path("reconciliationV2").path("registeredWrittenOffCopies").asLong());assertEquals(receipt,view.path("receipt"));
        assertEquals(receipt,response(post("/inventory-intakes/"+intake+"/confirm"),confirmation,"admin",200).path("data"));
        assertEquals(original,jdbc.queryForObject("SELECT request_json,preview_json,receipt_json FROM inventory_intake_request WHERE id=?",(rs,n)->List.of(rs.getString(1),rs.getString(2),rs.getString(3)),intake));
        JsonNode source=data(get("/book-copies/"+copyId+"/actions"),"librarian",200).path("intakeSource");assertEquals(intake,source.path("intakeId").asLong());assertEquals("MANUAL",source.path("sourceType").asText());assertTrue(source.get("purchaseItemId").isNull());
    }

    protected void installFailure(){jdbc.execute("CREATE TRIGGER copy_action_fail BEFORE UPDATE ON book_copy_action_request FOR EACH ROW CALL 'com.library.controller.BookCopyActionControllerTest$FailReceipt'");}
    public static class FailReceipt implements org.h2.api.Trigger{@Override public void fire(Connection c,Object[] old,Object[] next)throws SQLException{if("COMMITTED".equals(next[9]))throw new SQLException("synthetic-action-audit-failure");}}
    protected Book book(int t,int v){Book b=new Book();b.setIsbn("C"+unique().substring(0,19));b.setTitle("合成逐册动作");b.setStockTotal(t);b.setStockAvailable(v);b.setStatus(1);books.insert(b);return b;}
    protected BookCopy copy(Book b,String state,String location){BookCopy c=new BookCopy();c.setBookId(b.getId());c.setBarcode("000"+unique());c.setStatus(state);c.setLocation(location);copies.insert(c);return copies.selectById(c.getId());}
    protected long reader(){String name="act_"+unique().substring(0,12);jdbc.update("INSERT INTO sys_user(username,password,real_name,role,status) VALUES (?,'synthetic-only','合成读者','READER',1)",name);return actor(name);}
    protected long actor(String name){return jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,name);}
    protected void active(Book b,BookCopy c,long user){jdbc.update("INSERT INTO borrow_record(user_id,book_id,copy_id,borrow_date,due_date,due_date_kind,status) VALUES (?,?,?,'2020-01-01',NULL,'UNKNOWN','BORROWING')",user,b.getId(),c.getId());}
    protected ObjectNode request(String action,BookCopy...selected){ObjectNode r=json.createObjectNode().put("idempotencyKey",UUID.randomUUID().toString()).put("action",action).put("reason","合成现场依据");var arr=r.putArray("copies");for(BookCopy c:selected){BookCopy current=copies.selectById(c.getId());arr.add(json.createObjectNode().put("copyId",c.getId()).put("expectedVersion",current.getActionVersion()));}return r;}
    protected void location(ObjectNode r,String kind,String text){ObjectNode target=json.createObjectNode().put("kind",kind);if(text!=null)target.put("text",text);((ObjectNode)r.path("copies").get(0)).set("targetLocation",target);}
    protected void restore(ObjectNode r,BookCopy c,String text){((ObjectNode)r.path("copies").get(0)).put("restoreOfActionId",copies.selectById(c.getId()).getLastStateActionId()).put("restoreTo","DISABLED");location(r,text==null?"UNASSIGNED":"TEXT",text);}
    protected JsonNode preview(ObjectNode r,String role)throws Exception{return response(post("/book-copy-actions/previews"),r,role,200).path("data");}
    protected String confirmPath(JsonNode p){return "/book-copy-actions/"+p.path("requestId").asLong()+"/confirm";}
    protected ObjectNode confirmation(ObjectNode r,JsonNode p){return json.createObjectNode().put("idempotencyKey",r.path("idempotencyKey").asText()).put("previewDigest",p.path("previewDigest").asText());}
    protected JsonNode confirm(ObjectNode r,JsonNode p,String role)throws Exception{return response(post(confirmPath(p)),confirmation(r,p),role,200).path("data");}
    protected JsonNode v2(Book b)throws Exception{return data(get("/book-copies/catalog-summary").param("bookId",b.getId().toString()),"admin",200).path("records").get(0).path("reconciliationV2");}
    protected String unique(){return UUID.randomUUID().toString().replace("-","");}
    protected String bearer(String name){long id=actor(name);String role=jdbc.queryForObject("SELECT role FROM sys_user WHERE id=?",String.class,id);return "Bearer "+tokens.generateToken(id,name,role,0L);}
    protected JsonNode response(MockHttpServletRequestBuilder r,Object body,String role,int code)throws Exception{if(role!=null)r.header("Authorization",bearer(role));if(body!=null)r.contentType("application/json").content(body instanceof String s?s.getBytes(StandardCharsets.UTF_8):json.writeValueAsBytes(body));return json.readTree(mvc.perform(r).andExpect(status().is(code)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));}
    protected JsonNode data(MockHttpServletRequestBuilder r,String role,int code)throws Exception{return response(r,null,role,code).path("data");}
    protected void reason(MockHttpServletRequestBuilder r,Object body,String role,int code,String reason)throws Exception{JsonNode result=response(r,body,role,code);assertEquals(code,result.path("code").asInt());assertEquals(reason,result.path("data").path("reasonCode").asText());for(String field:List.of("requestId","copyId","ordinal"))assertTrue(result.path("data").has(field));}
    protected Map<String,Long> counts(){Map<String,Long> result=new TreeMap<>();for(String table:List.of("book","book_copy","borrow_record","book_copy_action","stock_flow","inventory_intake_copy","book_location"))result.put(table,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class));return result;}
}
