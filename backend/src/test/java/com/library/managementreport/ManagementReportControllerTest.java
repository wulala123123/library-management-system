package com.library.managementreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.entity.User;
import com.library.mapper.UserMapper;
import com.library.security.JwtTokenProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:management-report-only;DB_CLOSE_DELAY=-1;MODE=MYSQL;DATABASE_TO_LOWER=TRUE",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional(isolation=Isolation.REPEATABLE_READ)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManagementReportControllerTest {
    static final long MEMBER=71001,BOOK=72001,COPY=73001,FIRST=74001;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserMapper users;
    @Autowired JwtTokenProvider tokens;
    @Autowired ManagementReportService reports;
    LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));

    @BeforeAll void allowSyntheticHistoricalUnknownDateOnlyInOwnedTestDatabase() throws Exception {
        // V1 remains untouched. A dedicated synthetic schema permits an unknown
        // historical day so the report must preserve its explicit null meaning.
        try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            if("MySQL".equals(connection.getMetaData().getDatabaseProductName())) {
                assertTrue(connection.getCatalog().matches("library_repair_tests_report_[a-f0-9]{12}"));
                jdbc.execute("ALTER TABLE borrow_record MODIFY COLUMN borrow_date DATE NULL");
            } else {
                assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:management-report-only"));
                jdbc.execute("ALTER TABLE borrow_record ALTER COLUMN borrow_date DROP NOT NULL");
            }
        }
    }
    @BeforeEach void syntheticFixture() {
        jdbc.update("INSERT INTO sys_user(id,username,password,real_name,role,status,deleted) VALUES (?,?,'synthetic-only','report合成会员','READER',1,0)",MEMBER,"report-synthetic-reader");
        jdbc.update("INSERT INTO member_profile(user_id,source_key,source_system,source_archive_id,card_no) VALUES (?,'huiben:report-1','huiben',75001,'000001')",MEMBER);
        jdbc.update("INSERT INTO book(id,title,isbn,stock_total,stock_available) VALUES (?,'report合成书目','0000123456789',1,1)",BOOK);
        jdbc.update("INSERT INTO book_copy(id,book_id,barcode,status) VALUES (?,?,?,'AVAILABLE')",COPY,BOOK,"000Aa ");
        jdbc.update("INSERT INTO legacy_record(id,source_system,record_type,source_key,display_name,details_json,user_id,book_id) VALUES "
                +"(75001,'huiben','MEMBER','report-1','合成原档','{\"宝宝\":\"绝不输出\",\"原金额\":99999}',?,NULL),"
                +"(75002,'huiben','BOOK','000Aa ','合成原档','{\"原累计\":88888}',NULL,?)",MEMBER,BOOK);
        LocalDate[] dates={today.minusDays(6),today.minusDays(1),today,today,null,today.plusDays(1),today,today.minusDays(7)};
        for(int i=0;i<8;i++) {
            String source=i<2?"huiben:report-1:"+(i+1):i>=6?"unknown-source-"+i:null;
            String state=switch(i%3){case 0->"BORROWING";case 1->"RETURNED";default->"OVERDUE";};
            jdbc.update("INSERT INTO borrow_record(id,user_id,book_id,copy_id,source_key,status,borrow_date,due_date_kind,create_time,deleted) VALUES (?,?,?,?,?,?,?,'UNKNOWN','2026-10-21 10:00:00',0)",
                    FIRST+i,MEMBER,BOOK,COPY,source,state,dates[i]);
        }
        jdbc.update("INSERT INTO circulation_order(id,request_key,user_id,actor_id,created_at,business_date,item_count,remark) VALUES (76001,?,?,1,'2026-10-21 10:00:00','2026-10-21',2,'合成订单')","report-order-1".getBytes(StandardCharsets.UTF_8),MEMBER);
        for(int n=0;n<2;n++) jdbc.update("INSERT INTO circulation_order_item(order_id,ordinal,record_id,book_id,copy_id,book_title,barcode) VALUES (76001,?,?,?,?, '合成书目','000Aa ')",n+1,FIRST+2+n,BOOK,COPY);
    }

    @Test void reportHasExactSafeContractAndSeparateUnitsOrdersAndOriginCounts() throws Exception {
        JsonNode report=report("");
        assertEquals(Set.of("asOf","asOfDate","filters","totals","origins","trend","hotBooks","scope"),keys(report));
        assertEquals(today.toString(),report.path("asOfDate").asText());assertNotNull(Instant.parse(report.path("asOf").asText()));
        JsonNode totals=report.path("totals");assertEquals(8,totals.path("borrowDetails").asInt());assertEquals(8,totals.path("borrowedCopyEvents").asInt());
        assertEquals(5,totals.path("activeDetails").asInt());assertEquals(3,totals.path("returnedDetails").asInt());assertEquals(2,totals.path("overdueDetails").asInt());assertEquals(1,totals.path("knownCounterOrders").asInt());
        for(String field:List.of("imported","counterOrder","system","unverified")) assertEquals(2,report.path("origins").path(field).asInt());
        assertEquals("条",report.at("/origins/unit").asText());assertTrue(report.path("scope").asText().contains("不是不同实体册数"));
        assertEquals(Set.of("keyword","status","origin","startDate","endDate"),keys(report.path("filters")));
        for(String field:List.of("status","startDate","endDate"))assertTrue(report.path("filters").path(field).isNull());
        String whole=report.toString();for(String value:List.of("sourceKey","detailsJson","report合成会员","宝宝","99999","88888","password","financialFacts","cardNo"))assertFalse(whole.contains(value),value);
    }
    @Test void aggregateExactlyMatchesEveryPageAndAllExistingOriginStatusFilters() throws Exception {
        for(String origin:List.of("ALL","IMPORTED","COUNTER_ORDER","SYSTEM","UNVERIFIED"))
            for(String state:List.of("","ACTIVE","BORROWING","OVERDUE","RETURNED")) {
                String filter="keyword=report&origin="+origin+"&status="+state;
                JsonNode report=report(filter),first=inquiry(filter+"&page=1&size=3");
                assertEquals(first.path("summary"),report.path("origins"),filter);
                long rows=0;Set<Long> orders=new HashSet<>();
                for(int page=1;page<=Math.max(1,first.path("pages").asInt());page++)
                    for(JsonNode row:inquiry(filter+"&page="+page+"&size=3").path("records")) {
                        rows++;if(!row.path("counterOrderId").isNull())orders.add(row.path("counterOrderId").asLong());
                    }
                assertEquals(rows,report.at("/totals/borrowDetails").asLong(),filter);
                assertEquals(orders.size(),report.at("/totals/knownCounterOrders").asLong(),filter);
            }
    }
    @Test void sourceConflictsNeverBecomeKnownOrdersOrImportedCounts() throws Exception {
        jdbc.update("UPDATE circulation_order_item SET barcode='000Aa' WHERE order_id=76001 AND ordinal=1");
        jdbc.update("UPDATE borrow_record SET source_key='huiben:report-1:1 ' WHERE id=?",FIRST);
        JsonNode report=report("");assertEquals(1,report.at("/origins/counterOrder").asInt());assertEquals(1,report.at("/origins/imported").asInt());
        assertEquals(4,report.at("/origins/unverified").asInt());assertEquals(1,report.at("/totals/knownCounterOrders").asInt());
        assertEquals(inquiry("size=100").path("summary"),report.path("origins"));
    }
    @Test void keywordUsesExistingMemberTitleIsbnAndCompleteCaseSensitiveBarcodeSemantics() throws Exception {
        Map<String,Integer> examples=new LinkedHashMap<>();
        examples.put("000Aa ",8);examples.put("000Aa",0);examples.put("000aa ",0);
        examples.put("0000123456789",8);examples.put("report合成会员",8);examples.put("report合成书目",8);
        examples.put(" ",0);examples.put("no-synthetic-match",0);
        for(var example:examples.entrySet()) {
            JsonNode report=json.readTree(mvc.perform(get("/management-reports/borrows").param("keyword",example.getKey())
                    .header("Authorization",bearer(1))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
            JsonNode inquiry=json.readTree(mvc.perform(get("/borrow-inquiry").param("keyword",example.getKey()).param("size","100")
                    .header("Authorization",bearer(1))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
            assertEquals(example.getValue(),report.at("/totals/borrowDetails").asInt(),example.getKey());
            assertEquals(inquiry.path("summary"),report.path("origins"),example.getKey());
        }
    }
    @Test void reportIncludesDisabledAndDeletedCatalogHistoryButExcludesDeletedBorrowRows() throws Exception {
        jdbc.update("UPDATE book SET status=0 WHERE id=?",BOOK);
        jdbc.update("UPDATE borrow_record SET deleted=1 WHERE id=?",FIRST);
        assertEquals(7,report("").at("/totals/borrowDetails").asInt());
        assertEquals("report合成书目",report("").path("hotBooks").get(0).path("title").asText());
        jdbc.update("UPDATE book SET deleted=1 WHERE id=?",BOOK);
        JsonNode missing=report("");assertEquals(7,missing.at("/totals/borrowDetails").asInt());
        assertEquals("书目待核实",missing.path("hotBooks").get(0).path("title").asText());
        assertEquals(0,report("keyword=report合成书目").at("/totals/borrowDetails").asInt());
    }
    @Test void datesAreInclusiveAndUnknownDayIsIncludedOnlyWithoutADateFilter() throws Exception {
        assertEquals(8,report("").at("/totals/borrowDetails").asInt());
        assertEquals(3,report("startDate="+today+"&endDate="+today).at("/totals/borrowDetails").asInt());
        assertEquals(4,report("startDate="+today).at("/totals/borrowDetails").asInt());
        assertEquals(6,report("endDate="+today).at("/totals/borrowDetails").asInt());
        assertEquals(0,report("startDate="+today+"&endDate="+today+"&origin=IMPORTED").at("/totals/borrowDetails").asInt());
    }
    @Test void trendUsesSevenShanghaiNaturalDaysAndZeroFillsWithoutInventingUnknownDates() throws Exception {
        JsonNode trend=report("").path("trend");assertEquals(7,trend.size());int count=0;
        for(int i=0;i<7;i++){assertEquals(today.minusDays(6-i).toString(),trend.get(i).path("date").asText());count+=trend.get(i).path("borrowDetails").asInt();}
        assertEquals(5,count);assertEquals(0,trend.get(1).path("borrowDetails").asInt());assertEquals(1,trend.get(6).path("knownCounterOrders").asInt());
        ManagementReportService fixed=new ManagementReportService(jdbc,Clock.fixed(Instant.parse("2026-01-01T16:01:00Z"),ZoneOffset.UTC));
        var result=fixed.report(null,null,null,null,null);assertEquals("2026-01-02",result.asOfDate());assertEquals("2025-12-27",result.trend().get(0).date());
    }
    @Test void topTenHasStableBookIdTieBreakAndMissingBooksAreExplicit() throws Exception {
        for(int i=0;i<12;i++) {
            long book=72200+i;jdbc.update("INSERT INTO book(id,title,isbn) VALUES (?,? ,?)",book,"排行"+i,"rank-"+i);
            for(int n=0;n<2;n++) jdbc.update("INSERT INTO borrow_record(id,user_id,book_id,borrow_date,due_date_kind,status,deleted) VALUES (?,?,?,?,'UNKNOWN','RETURNED',0)",78000+i*2+n,MEMBER,book,today);
        }
        JsonNode hot=report("").path("hotBooks");assertEquals(10,hot.size());assertEquals(BOOK,hot.get(0).path("bookId").asLong());
        for(int i=1;i<10;i++)assertEquals(72199+i,hot.get(i).path("bookId").asLong());
        jdbc.update("UPDATE book SET deleted=1 WHERE id=?",BOOK);
        assertEquals("书目待核实",report("").path("hotBooks").get(0).path("title").asText());
        assertEquals(8,report("").path("hotBooks").get(0).path("borrowDetails").asInt());
    }
    @Test void summaryCsvUsesSameFiltersTotalsTrendAndRankingAndSafeText() throws Exception {
        var response=export("status=ACTIVE&origin=COUNTER_ORDER");String text=response.getContentAsString(StandardCharsets.UTF_8);
        assertArrayEquals(new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf},Arrays.copyOf(response.getContentAsByteArray(),3));
        assertEquals("2",response.getHeader("X-Total-Count"));assertTrue(response.getHeader("Content-Disposition").matches("attachment; filename=\"management-borrows-[0-9]{8}T[0-9]{6}Z.csv\""));
        assertTrue(text.contains("\"导出时点\",\""+response.getHeader("X-As-Of")+"\""));
        assertTrue(text.contains("\"汇总\",\"借出册次（按明细）\",\"2\",\"册次\""));
        assertTrue(text.contains("\"汇总\",\"明确柜台订单（去重）\",\"1\",\"单\""));assertTrue(text.contains("\"来源\",\"新柜台\",\"2\",\"条\""));
        assertTrue(text.contains("\"趋势\",\""+today+"\",\"2\",\"1\""));
        assertTrue(text.contains("\"排行\",\""+BOOK+"\",\"report合成书目\",\"2\",\"1\""));
        jdbc.update("UPDATE book SET title=? WHERE id=?","@SUM(1,2)\r\n\"保留\"",BOOK);
        String escaped=export("").getContentAsString(StandardCharsets.UTF_8);assertTrue(escaped.contains("\"'@SUM(1,2)\r\n\"\"保留\"\"\""));
        var value=reports.report(null,null,null,null,null);
        var dangerous=new ManagementReportContracts.Report(value.asOf(),value.asOfDate(),new ManagementReportContracts.Filters("\u3000=1",null,"ALL",null,null),value.totals(),value.origins(),value.trend(),value.hotBooks(),value.scope());
        assertTrue(new String(ManagementReportCsv.render(dangerous),StandardCharsets.UTF_8).contains("\"关键词\",\"'\u3000=1\""));
    }
    @Test void emptyAndOverTwentyThousandAreValidAggregateReportsNotDetailExports() throws Exception {
        assertEquals(0,report("keyword=no-synthetic-match").at("/totals/borrowDetails").asInt());assertEquals("0",export("keyword=no-synthetic-match").getHeader("X-Total-Count"));
        List<Object[]> bulk=new ArrayList<>();for(int i=0;i<20001;i++)bulk.add(new Object[]{80000+i,MEMBER,BOOK,today});
        jdbc.batchUpdate("INSERT INTO borrow_record(id,user_id,book_id,borrow_date,due_date_kind,status,deleted) VALUES (?,?,?,?,'UNKNOWN','RETURNED',0)",bulk);
        var response=export("");assertEquals("20009",response.getHeader("X-Total-Count"));assertTrue(response.getContentAsByteArray().length<15000);
    }
    @ParameterizedTest @ValueSource(strings={"page=1","size=10","keyword=a&keyword=b","status=BAD","origin=bad","startDate=2026-02-30","startDate=20260101","startDate=0000-01-01","endDate=2026-01-01T00:00:00","startDate=2026-02-01&endDate=2026-01-01"})
    void invalidFiltersNeverReturnCsvOrSuccess(String filter) throws Exception {
        for(String path:List.of("/management-reports/borrows","/management-reports/borrows/export")) {
            var response=mvc.perform(get(path+"?"+filter).header("Authorization",bearer(1))).andExpect(status().isBadRequest()).andReturn().getResponse();
            assertNull(response.getHeader("Content-Disposition"));assertNull(response.getHeader("X-Total-Count"));
        }
    }
    @Test void reportAndCsvAreAdminOnlyAndEverySuccessfulOrRejectedReadLeavesAllTablesUnchanged() throws Exception {
        Map<String,List<String>> before=snapshot();report("");export("origin=IMPORTED");
        for(String path:List.of("/management-reports/borrows","/management-reports/borrows/export")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            for(long actor:List.of(2L,3L))mvc.perform(get(path).header("Authorization",bearer(actor))).andExpect(status().isForbidden());
            mvc.perform(get(path+"?page=1").header("Authorization",bearer(1))).andExpect(status().isBadRequest());
        }
        assertEquals(before,snapshot());Transactional tx=ManagementReportService.class.getAnnotation(Transactional.class);
        assertTrue(tx.readOnly());assertEquals(Isolation.REPEATABLE_READ,tx.isolation());
    }

    String bearer(long actor){User user=users.selectById(actor);return "Bearer "+tokens.generateToken(user.getId(),user.getUsername(),user.getRole(),user.getTokenVersion());}
    JsonNode report(String query)throws Exception{return json.readTree(mvc.perform(get("/management-reports/borrows?"+query).header("Authorization",bearer(1))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");}
    JsonNode inquiry(String query)throws Exception{return json.readTree(mvc.perform(get("/borrow-inquiry?"+query).header("Authorization",bearer(1))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");}
    MockHttpServletResponse export(String query)throws Exception{return mvc.perform(get("/management-reports/borrows/export?"+query).header("Authorization",bearer(1))).andExpect(status().isOk()).andReturn().getResponse();}
    private Set<String> keys(JsonNode node){Set<String> keys=new HashSet<>();node.fieldNames().forEachRemaining(keys::add);return keys;}
    private Map<String,List<String>> snapshot()throws Exception {
        List<String> tables=jdbc.execute((ConnectionCallback<List<String>>)c->{List<String> names=new ArrayList<>();try(var rs=c.getMetaData().getTables(c.getCatalog(),c.getSchema(),"%",new String[]{"TABLE"})){while(rs.next())names.add(rs.getString("TABLE_NAME"));}return names;});
        Map<String,List<String>> result=new TreeMap<>();
        for(String table:tables){assertTrue(table.matches("[A-Za-z_0-9]+"));List<String> rows=new ArrayList<>();for(var row:jdbc.queryForList("SELECT * FROM "+table))rows.add(json.writeValueAsString(row));Collections.sort(rows);result.put(table,rows);}return result;
    }
}
