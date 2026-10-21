package com.library.borrowquery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.entity.User;
import com.library.history.HistoryContextService;
import com.library.mapper.UserMapper;
import com.library.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:borrow-inquiry-only;DB_CLOSE_DELAY=-1;MODE=MYSQL;DATABASE_TO_LOWER=TRUE",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional(isolation=Isolation.REPEATABLE_READ)
class BorrowInquiryControllerTest {
    static final long MEMBER=71001, BOOK=72001, OTHER_BOOK=72002, COPY=73001, OTHER_COPY=73002, FIRST=74001;
    static final String BARCODE="000Aa ";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserMapper users;
    @Autowired JwtTokenProvider tokens;
    @Autowired HistoryContextService history;

    @BeforeEach void syntheticData() {
        jdbc.update("INSERT INTO sys_user(id,username,password,real_name,role,status,deleted) VALUES (?,?,'synthetic-only','inquiry合成会员','READER',1,0)", MEMBER,"inquiry-synthetic-reader");
        jdbc.update("INSERT INTO member_profile(user_id,source_key,source_system,source_archive_id,card_no) VALUES (?,'huiben:inquiry-1','huiben',75001,'000001')",MEMBER);
        jdbc.update("INSERT INTO book(id,title,isbn,stock_total,stock_available) VALUES (?,'inquiry合成书目','0000123456789',1,1),(?,'其他合成书目','inquiry-other',1,1)",BOOK,OTHER_BOOK);
        jdbc.update("INSERT INTO book_copy(id,book_id,barcode,status) VALUES (?,?,?,'AVAILABLE'),(?,?,?,'AVAILABLE')",COPY,BOOK,BARCODE,OTHER_COPY,OTHER_BOOK,"000-other");
        archive(75001,"MEMBER","inquiry-1",MEMBER,null,"{\"历史累计次数\":99999,\"宝宝\":\"绝不输出\",\"原金额\":99999}");
        archive(75002,"BOOK",BARCODE,null,BOOK,"{\"原系统累计借阅数\":88888}");
        archive(75003,"ORDER","old-order-only",MEMBER,null,"{\"状态\":\"原状态\"}");
        for(int i=0;i<8;i++) {
            String source=i<2?"huiben:inquiry-1:"+(i+1):i>=6?"unknown-source-"+i:null;
            String state=switch(i%3){case 0->"BORROWING";case 1->"RETURNED";default->"OVERDUE";};
            jdbc.update("INSERT INTO borrow_record(id,user_id,book_id,copy_id,source_key,status,borrow_date,due_date,return_date,due_date_kind,renew_count,remark,create_time,deleted) "
                    + "VALUES (?,?,?,?,?,?,'2026-01-02',NULL,NULL,?,0,NULL,'2026-10-21 10:00:00',0)",FIRST+i,MEMBER,BOOK,COPY,source,state,i==1?"UNKNOWN":"UNLIMITED");
        }
        counter(FIRST+2,76001); counter(FIRST+3,76002);
    }

    @Test void fourOriginsSummaryAndHistoryAreIdenticalWithoutAddingLegacyCumulativeCounts() throws Exception {
        JsonNode page=page("size=100",1);
        assertEquals(8,page.path("total").asInt()); assertEquals(1,page.path("pages").asInt());
        for(String field:List.of("imported","counterOrder","system","unverified"))assertEquals(2,page.at("/summary/"+field).asInt());
        assertEquals("条",page.at("/summary/unit").asText());
        assertTrue(page.at("/summary/scope").asText().contains("不与原系统累计相加"));
        for(JsonNode row:page.path("records")) {
            var context=history.context("borrows",row.path("id").asLong());
            assertEquals(context.provenance().classification(),row.path("origin").asText());
            assertEquals(context.links().counterOrderId(),row.path("counterOrderId").isNull()?null:row.path("counterOrderId").asText());
        }
        assertNotNull(Instant.parse(page.path("asOf").asText()));
        assertEquals("",page.at("/filters/keyword").asText()); assertTrue(page.at("/filters/status").isNull());
    }

    @ParameterizedTest @ValueSource(strings={"IMPORTED","COUNTER_ORDER","SYSTEM","UNVERIFIED"})
    void originFilterSummaryCountsOnlyTheFilteredSet(String origin) throws Exception {
        JsonNode page=page("origin="+origin,1);
        assertEquals(2,page.path("total").asInt());
        assertEquals(2,page.at("/summary/total").asInt());
        assertEquals(2,summarySum(page));
        page.path("records").forEach(r->assertEquals(origin,r.path("origin").asText()));
        assertEquals(origin,page.at("/filters/origin").asText());
    }

    @Test void stableThreePagesHaveUniqueIdsWhenAllCreateTimesAreEqual() throws Exception {
        List<Long> actual=new ArrayList<>();
        for(int p=1;p<=3;p++) {
            JsonNode page=page("page="+p+"&size=3",1);
            assertEquals(3,page.path("pages").asInt());assertEquals(8,page.path("total").asInt());
            actual.addAll(ids(page));
        }
        assertEquals(List.of(74008L,74007L,74006L,74005L,74004L,74003L,74002L,74001L),actual);
        assertEquals(8,new HashSet<>(actual).size());
        assertTrue(page("page=99&size=3",1).path("records").isEmpty());
    }

    @Test void keywordStatusAndOriginApplyAsAnIntersectionToRowsCountAndExport() throws Exception {
        JsonNode page=page("keyword=inquiry&status=ACTIVE&origin=IMPORTED",1);
        assertEquals(List.of(FIRST),ids(page));assertEquals(1,summarySum(page));
        assertEquals(1,page.at("/summary/imported").asInt());
        assertEquals(3,page("status=BORROWING",1).path("total").asInt());
        assertEquals(3,page("status=RETURNED",1).path("total").asInt());
        assertEquals(2,page("status=OVERDUE",1).path("total").asInt());
        var exported=export("keyword=inquiry&status=ACTIVE&origin=IMPORTED",1);
        assertEquals("1",exported.getHeader("X-Total-Count"));
        assertEquals(List.of(FIRST),csvIds(exported));
    }

    @Test void exactBarcodeSearchRetainsCaseLeadingZerosAndTrailingSpaces() throws Exception {
        var exact=mvc.perform(get("/borrow-inquiry").param("keyword",BARCODE).header("Authorization",bearer(1))).andExpect(status().isOk()).andReturn().getResponse();
        assertEquals(8,json.readTree(exact.getContentAsByteArray()).at("/data/total").asInt());
        for(String wrong:List.of("000aa","000Aa","000Aa  ")) {
            var response=mvc.perform(get("/borrow-inquiry").param("keyword",wrong).header("Authorization",bearer(1))).andExpect(status().isOk()).andReturn().getResponse();
            assertEquals(0,json.readTree(response.getContentAsByteArray()).at("/data/total").asInt());
        }
    }

    @ParameterizedTest @ValueSource(strings={"member","book","copy","barcode","source","archive-owner","archive-book","member-source"})
    void conflictsAreUnverifiedInBothInquiryAndHistory(String conflict) throws Exception {
        long target=FIRST;
        switch(conflict) {
            case "member"->jdbc.update("UPDATE sys_user SET deleted=1 WHERE id=?",MEMBER);
            case "book"->jdbc.update("UPDATE book SET deleted=1 WHERE id=?",BOOK);
            case "copy"->jdbc.update("UPDATE borrow_record SET copy_id=? WHERE id=?",OTHER_COPY,FIRST);
            case "barcode"->jdbc.update("UPDATE legacy_record SET source_key='000aa' WHERE id=75002");
            case "source"->jdbc.update("UPDATE borrow_record SET source_key='huiben:inquiry-1:1 ' WHERE id=?",FIRST);
            case "archive-owner"->jdbc.update("UPDATE legacy_record SET user_id=3 WHERE id=75002");
            case "archive-book"->jdbc.update("UPDATE legacy_record SET book_id=? WHERE id=75002",OTHER_BOOK);
            case "member-source"->jdbc.update("UPDATE legacy_record SET source_system='Huiben' WHERE id=75001");
        }
        JsonNode row=find(page("size=100",1),target);
        assertEquals("UNVERIFIED",row.path("origin").asText());
        assertEquals(history.context("borrows",target).provenance().classification(),row.path("origin").asText());
        if("copy".equals(conflict)||"book".equals(conflict))assertTrue(row.path("copyBarcode").isNull());
    }

    @Test void counterOrderUsesOnlyTheExactRecordAndChecksAllStoredIdentities() throws Exception {
        jdbc.update("UPDATE circulation_order_item SET barcode='000Aa' WHERE order_id=76001");
        jdbc.update("UPDATE circulation_order SET user_id=3 WHERE id=76002");
        JsonNode all=page("size=100",1);
        for(long id:List.of(FIRST+2,FIRST+3)) {
            assertEquals("UNVERIFIED",find(all,id).path("origin").asText());
            assertTrue(find(all,id).path("counterOrderId").isNull());
            assertEquals("UNVERIFIED",history.context("borrows",id).provenance().classification());
        }
        assertEquals("SYSTEM",find(all,FIRST+4).path("origin").asText());
        assertTrue(find(all,FIRST+4).path("counterOrderId").isNull());
    }

    @Test void staffProjectionHasNoProtectedSourcesOrDeletedEntityNames() throws Exception {
        JsonNode row=find(page("",2),FIRST);
        assertEquals(20,row.size()); assertTrue(row.path("id").isIntegralNumber());
        assertEquals(0,row.path("fineAmount").asInt()); assertEquals(0,row.path("renewCount").asInt());
        assertTrue(row.path("remark").isNull()); assertTrue(row.path("dueDate").isNull());
        assertEquals("2026-01-02",row.path("borrowDate").asText());
        String whole=page("",2).toString();
        for(String forbidden:List.of("sourceKey","source_key","detailsJson","宝宝","password","cardNo","paidFee","99999","88888"))assertFalse(whole.contains(forbidden),forbidden);
        jdbc.update("UPDATE sys_user SET deleted=1 WHERE id=?",MEMBER);
        jdbc.update("UPDATE book SET deleted=1 WHERE id=?",BOOK);
        JsonNode deleted=find(page("",2),FIRST);
        assertTrue(deleted.path("userName").isNull());assertTrue(deleted.path("bookTitle").isNull());assertTrue(deleted.path("copyBarcode").isNull());
        assertEquals(8,page("",2).path("total").asInt());
    }

    @Test void softDeletedBorrowIsExcludedAndUnknownSourceDoesNotBecomeAnOldOrder() throws Exception {
        jdbc.update("UPDATE borrow_record SET deleted=1 WHERE id=?",FIRST);
        assertEquals(7,page("",1).path("total").asInt());
        assertEquals(1,page("origin=IMPORTED",1).path("total").asInt());
        assertEquals("UNVERIFIED",find(page("",1),FIRST+6).path("origin").asText());
        assertTrue(find(page("",1),FIRST+6).path("counterOrderId").isNull());
    }

    @Test void completeCsvContainsAllPagesMetadataStableIdsAndOriginalDates() throws Exception {
        JsonNode firstPage=page("size=3",1);assertEquals(3,firstPage.path("records").size());
        var response=export("",2);
        assertEquals("8",response.getHeader("X-Total-Count"));assertNotNull(Instant.parse(response.getHeader("X-As-Of")));
        assertTrue(response.getHeader("Content-Disposition").matches("attachment; filename=\"borrow-inquiry-[0-9]{8}T[0-9]{6}Z.csv\""));
        byte[] bytes=response.getContentAsByteArray();assertArrayEquals(new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf},Arrays.copyOf(bytes,3));
        String csv=new String(bytes,StandardCharsets.UTF_8);assertTrue(csv.contains("\"导出时点\",\""+response.getHeader("X-As-Of")+"\""));
        assertTrue(csv.contains("\"总量\",\"8\""));assertTrue(csv.contains("\"单位\",\"条\""));
        assertTrue(csv.contains("不与原系统累计相加")); assertTrue(csv.contains("\"2026-01-02\",\"UNKNOWN\",\"\",\"\""));
        assertTrue(csv.contains("\"0000123456789\""));assertTrue(csv.contains("\"000Aa \""));
        assertEquals(List.of(74008L,74007L,74006L,74005L,74004L,74003L,74002L,74001L),csvIds(response));
        for(String forbidden:List.of("sourceKey","huiben:inquiry-1","old-order-only","历史累计","宝宝","88888","99999"))assertFalse(csv.contains(forbidden),forbidden);
    }

    @Test void csvEscapesCommasQuotesNewlinesAndFormulaPrefixesWithoutLosingZeroOrNull() throws Exception {
        String remark="=HYPERLINK(\"bad\",\"x\")\r\n第二行,\"保留\"";
        jdbc.update("UPDATE borrow_record SET remark=? WHERE id=?",remark,FIRST);
        jdbc.update("UPDATE book SET title=? WHERE id=?","@SUM(1,2)",BOOK);
        var csv=export("origin=IMPORTED",1);
        var records=parseCsv(csv.getContentAsString(StandardCharsets.UTF_8));
        int header=headerIndex(records);List<String> first=records.get(header+2);
        assertEquals("'"+remark,first.get(16));assertEquals("'@SUM(1,2)",first.get(4));
        assertEquals("0000123456789",first.get(5));assertEquals(BARCODE,first.get(7));
        assertEquals("0",first.get(15));assertEquals(0,new java.math.BigDecimal(first.get(17)).compareTo(java.math.BigDecimal.ZERO));assertEquals("",first.get(13));
        assertEquals(2,csvIds(csv).size());
    }

    @Test void formulaProtectionAlsoCoversLeadingWhitespaceAndCsvMetadata() throws Exception {
        for(String danger:List.of("\t=1","\r=1","\n=1"," +1","\u3000-1","@command")) {
            var row=new BorrowInquiryContracts.Row(1,1,1,null,danger,danger,"safe","0001",null,"00001", "2020-01-01",null,null,"UNKNOWN",0,"RETURNED",danger,java.math.BigDecimal.ZERO,"SYSTEM",null);
            String text=new String(BorrowInquiryCsv.render(List.of(row),new BorrowInquiryContracts.Filters(danger,null,"ALL"),"2026-01-01T00:00:00Z",BorrowInquiryService.SCOPE),StandardCharsets.UTF_8);
            var cells=parseCsv(text);assertEquals("'"+danger,cells.get(1).get(1));
            assertEquals("'"+danger,cells.get(headerIndex(cells)+1).get(16));
        }
    }

    @Test void emptyFilterResultHasNormalSummaryAndCsvHeaderWithNoRows() throws Exception {
        JsonNode empty=page("keyword=absent-synthetic",1);
        assertEquals(0,empty.path("total").asInt());assertEquals(0,empty.path("pages").asInt());assertEquals(0,summarySum(empty));
        assertTrue(empty.path("records").isEmpty());
        var csv=export("keyword=absent-synthetic",1);assertEquals("0",csv.getHeader("X-Total-Count"));assertTrue(csvIds(csv).isEmpty());
    }

    @ParameterizedTest @ValueSource(strings={"page=0","page=bad","size=0","size=101","status=BAD","origin=bad"})
    void invalidQueryParametersAreRejected(String query) throws Exception {
        mvc.perform(get("/borrow-inquiry?"+query).header("Authorization",bearer(1))).andExpect(status().isBadRequest());
    }

    @Test void exportRefusesPageArgumentsAndOversizeBeforeReturningAnyCsv() throws Exception {
        mvc.perform(get("/borrow-inquiry/export?page=1").header("Authorization",bearer(1))).andExpect(status().isBadRequest());
        mvc.perform(get("/borrow-inquiry/export?size=10").header("Authorization",bearer(1))).andExpect(status().isBadRequest());
        mvc.perform(get("/borrow-inquiry/export?origin=BAD").header("Authorization",bearer(1))).andExpect(status().isBadRequest());
        List<Object[]> values=new ArrayList<>();for(int i=0;i<20001;i++)values.add(new Object[]{80000+i,MEMBER,BOOK});
        jdbc.batchUpdate("INSERT INTO borrow_record(id,user_id,book_id,borrow_date,due_date_kind,status,remark,create_time,deleted) VALUES (?,?,?,'2026-01-02','UNKNOWN','RETURNED','bulk-limit','2026-10-21 10:00:00',0)",values);
        // The keyword is in the member/title, as in the existing borrower query.
        var response=mvc.perform(get("/borrow-inquiry/export?keyword=inquiry").header("Authorization",bearer(1)))
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        assertNull(response.getHeader("Content-Disposition"));assertNull(response.getHeader("X-Total-Count"));
        assertFalse(response.getContentType().contains("csv"));assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("20000"));
    }

    @Test void anonymousAndReaderCannotReadInquiryOrCsvAndStaffCannotReadArchives() throws Exception {
        for(String path:List.of("/borrow-inquiry","/borrow-inquiry/export")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization",bearer(3))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/history-context/borrows/"+FIRST).header("Authorization",bearer(2))).andExpect(status().isForbidden());
        mvc.perform(get("/legacy-data/records/75001").header("Authorization",bearer(2))).andExpect(status().isForbidden());
        assertEquals(8,page("",2).path("total").asInt());assertEquals("8",export("",2).getHeader("X-Total-Count"));
    }

    @Test void allReadsAndRejectedReadsPreserveEveryBusinessRow() throws Exception {
        var before=snapshot();page("size=3",1);page("page=2&size=3&origin=ALL",2);page("status=ACTIVE&origin=IMPORTED",1);
        export("",1);export("status=RETURNED",2);
        mvc.perform(get("/borrow-inquiry/export?page=1").header("Authorization",bearer(1))).andExpect(status().isBadRequest());
        assertEquals(before,snapshot());
        Transactional tx=BorrowInquiryService.class.getAnnotation(Transactional.class);
        assertTrue(tx.readOnly());assertEquals(Isolation.REPEATABLE_READ,tx.isolation());
    }

    protected void archive(long id,String type,String key,Long member,Long book,String raw) {
        jdbc.update("INSERT INTO legacy_record(id,source_system,record_type,source_key,display_name,details_json,user_id,book_id) VALUES (?,'huiben',?,?,'合成原档',?,?,?)",id,type,key,raw,member,book);
    }
    protected void counter(long record,long order) {
        jdbc.update("INSERT INTO circulation_order(id,request_key,user_id,actor_id,created_at,business_date,item_count,remark) VALUES (?,?,?,?, '2026-10-21 10:00:00','2026-10-21',1,'合成订单')",order,("inquiry-order-"+order).getBytes(StandardCharsets.UTF_8),MEMBER,1);
        jdbc.update("INSERT INTO circulation_order_item(order_id,ordinal,record_id,book_id,copy_id,book_title,barcode) VALUES (?,1,?,?,?,'合成书目',?)",order,record,BOOK,COPY,BARCODE);
    }
    protected String bearer(long actor) { User u=users.selectById(actor);return "Bearer "+tokens.generateToken(u.getId(),u.getUsername(),u.getRole(),u.getTokenVersion()); }
    protected JsonNode page(String query,long actor)throws Exception {var response=mvc.perform(get("/borrow-inquiry?"+query).header("Authorization",bearer(actor))).andExpect(status().isOk()).andReturn().getResponse();return json.readTree(response.getContentAsByteArray()).path("data");}
    protected MockHttpServletResponse export(String query,long actor)throws Exception {return mvc.perform(get("/borrow-inquiry/export?"+query).header("Authorization",bearer(actor))).andExpect(status().isOk()).andReturn().getResponse();}
    private List<Long> ids(JsonNode page){List<Long> out=new ArrayList<>();page.path("records").forEach(r->out.add(r.path("id").asLong()));return out;}
    private JsonNode find(JsonNode page,long id){for(JsonNode r:page.path("records"))if(r.path("id").asLong()==id)return r;throw new AssertionError("Missing row "+id);}
    private int summarySum(JsonNode page){JsonNode s=page.path("summary");return s.path("imported").asInt()+s.path("counterOrder").asInt()+s.path("system").asInt()+s.path("unverified").asInt();}
    private List<Long> csvIds(MockHttpServletResponse response)throws Exception {var rows=parseCsv(response.getContentAsString(StandardCharsets.UTF_8));int header=headerIndex(rows);List<Long> ids=new ArrayList<>();for(int i=header+1;i<rows.size();i++)ids.add(Long.parseLong(rows.get(i).get(0)));return ids;}
    private int headerIndex(List<List<String>> rows){for(int i=0;i<rows.size();i++)if(rows.get(i).get(0).equals("借阅ID"))return i;throw new AssertionError("CSV data header missing");}
    private List<List<String>> parseCsv(String raw){String text=raw.startsWith("\uFEFF")?raw.substring(1):raw;List<List<String>> out=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false;for(int i=0;i<text.length();i++){char c=text.charAt(i);if(c=='"'){if(quoted&&i+1<text.length()&&text.charAt(i+1)=='"'){cell.append('"');i++;}else quoted=!quoted;}else if(!quoted&&c==','){row.add(cell.toString());cell.setLength(0);}else if(!quoted&&c=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n'){row.add(cell.toString());cell.setLength(0);out.add(row);row=new ArrayList<>();i++;}else cell.append(c);}assertFalse(quoted);return out;}
    private Map<String,String> snapshot()throws Exception{Map<String,String> out=new TreeMap<>();for(String table:List.of("sys_user","member_profile","book","book_copy","borrow_record","fine_record","legacy_record","circulation_order","circulation_order_item")){String order=table.equals("member_profile")?"user_id":table.equals("circulation_order_item")?"order_id,ordinal":"id";out.put(table,json.writeValueAsString(jdbc.queryForList("SELECT * FROM "+table+" ORDER BY "+order)));}return out;}
}
