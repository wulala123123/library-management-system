package com.library.managementexport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.entity.User;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:management-export-only;DB_CLOSE_DELAY=-1;MODE=MYSQL;DATABASE_TO_LOWER=TRUE",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional(isolation=Isolation.REPEATABLE_READ)
class ManagementExportControllerTest {
    static final long MEMBER=91001,BOOK=92001,CATEGORY=93001,COPY=94001;
    static final String KEY="export-synth",BARCODE="000Aa ";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired UserMapper users;
    @Autowired JwtTokenProvider tokens;

    @BeforeEach void syntheticRows() {
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        jdbc.update("INSERT INTO book_category(id,name,status,deleted) VALUES (?,'合成分类一',1,0),(?,'合成分类二',1,0)",CATEGORY,CATEGORY+1);
        List<Object[]> accounts=new ArrayList<>(),profiles=new ArrayList<>(),books=new ArrayList<>(),copies=new ArrayList<>(),attributes=new ArrayList<>(),ages=new ArrayList<>();
        for(int i=0;i<205;i++) {
            long member=MEMBER+i,book=BOOK+i;boolean deleted=i==204;
            accounts.add(new Object[]{member,KEY+"-user-"+i,KEY+"-姓名-"+i,i%2==0?1:0,deleted?1:0});
            LocalDate start=i%4==3?null:i%4==1?today.plusDays(2):today.minusDays(20);
            LocalDate end=i%4==3?null:i%4==2?today.minusDays(1):today.plusDays(20);
            String usage=i%4==0?"LIMITED":i%4==1?"UNLIMITED":"UNKNOWN";
            profiles.add(new Object[]{member,"manual:export-"+i,String.format("%08d",i),"合成套餐",start,end,usage,i%4==0?0:null,i%4==0?0:null});
            books.add(new Object[]{book,String.format("%013d",i),KEY+"-登记书名-"+i,KEY+"-作者",i%2==0?null:"",CATEGORY+i%2,i%3==0?BigDecimal.ZERO:null,i%3==0?0:null,i%5,i%5,i%2,deleted?1:0});
            copies.add(new Object[]{COPY+i,book,i==0?BARCODE:"000-export-"+i});
            if(i%4<3) {
                attributes.add(new Object[]{book,i%4==0?KEY+"-展示书名-"+i:null,i%4==0?"adopted-special-"+i:null,i%4==0?"BOARD":i%4==1?"HARDCOVER":null,i%4<2?1:0});
                if(i%4==0){ages.add(new Object[]{book,"AGE_0_2"});ages.add(new Object[]{book,"AGE_3_4"});}
                if(i%4==1)ages.add(new Object[]{book,"AGE_7_9"});
            }
        }
        jdbc.batchUpdate("INSERT INTO sys_user(id,username,password,real_name,phone,email,role,status,deleted) VALUES (?,?,'private-password-marker',?,'13000000999',NULL,'READER',?,?)",accounts);
        jdbc.batchUpdate("INSERT INTO member_profile(user_id,source_key,card_no,plan_name,service_start,service_end,usage_type,used_uses,total_uses,notes,address,paid_fee,deposit_amount,deposit_status) VALUES (?,?,?,?,?,?,?,?,?,'private-notes-marker','private-address-marker',666.99,777.99,'private-financial-marker')",profiles);
        jdbc.batchUpdate("INSERT INTO book(id,isbn,title,author,publisher,category_id,price,pages,stock_total,stock_available,status,deleted,create_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'2026-01-01 12:00:00')",books);
        jdbc.batchUpdate("INSERT INTO book_copy(id,book_id,barcode,status) VALUES (?,?,?,'AVAILABLE')",copies);
        jdbc.batchUpdate("INSERT INTO catalog_book_attribute(book_id,display_title_override,adopted_source_title,binding_code,age_known,display_origin,binding_origin,age_origin,updated_at) VALUES (?,?,?,?,?,'MANUAL','MANUAL','MANUAL','2026-01-01 12:00:00')",attributes);
        jdbc.batchUpdate("INSERT INTO catalog_book_age_label(book_id,label_code) VALUES (?,?)",ages);
        jdbc.update("INSERT INTO member_child(user_id,name,notes) VALUES (?,'private-child-marker','private-child-notes')",MEMBER);
        jdbc.update("INSERT INTO legacy_record(source_system,record_type,source_key,display_name,details_json,user_id) VALUES ('huiben','MEMBER','export-private','private-source-marker','{\"privateJson\":true}',?)",MEMBER);
    }

    @Test void completeMembersMatchActualListAcrossThreePagesAndStayPrivate() throws Exception {
        var file=export("members","keyword="+KEY,1);var csv=data(file,"会员ID");
        assertEquals(204,csv.size());assertEquals("204",file.getHeader("X-Total-Count"));
        assertEquals(listIds("/members","keyword="+KEY),csvIds(csv));
        String text=file.getContentAsString(StandardCharsets.UTF_8);
        for(String forbidden:List.of("private-password","private-notes","private-address","private-financial","private-child","private-source","privateJson","666.99","777.99"))assertFalse(text.contains(forbidden),forbidden);
        List<String> first=csv.stream().filter(r->r.get(0).equals(Long.toString(MEMBER))).findFirst().orElseThrow();
        assertEquals("00000000",first.get(7));assertEquals("0",first.get(14));assertEquals("0",first.get(15));assertEquals("0",first.get(16));
    }

    @ParameterizedTest @ValueSource(strings={"ACTIVE","WAITING","EXPIRED","UNKNOWN"})
    void memberAccountAndServiceFiltersUseTheActualListPredicate(String state) throws Exception {
        String query="keyword="+KEY+"&serviceStatus="+state+"&accountStatus="+(state.equals("WAITING")||state.equals("UNKNOWN")?0:1);
        var csv=data(export("members",query,1),"会员ID");
        assertFalse(csv.isEmpty());assertEquals(listIds("/members",query),csvIds(csv));
        for(var row:csv)assertEquals(state,row.get(11));
    }

    @Test void memberKeywordEscapesWildcardsAndKeepsSharedPhoneIdentitiesSeparate() throws Exception {
        jdbc.update("UPDATE sys_user SET real_name='export-synth_100%' WHERE id=?",MEMBER);
        var exact=mvc.perform(get("/management-exports/members").param("keyword","_100%").header("Authorization",bearer(1))).andExpect(status().isOk()).andReturn().getResponse();
        assertEquals(List.of(MEMBER),csvIds(data(exact,"会员ID")));
        var byPhone=data(export("members","keyword=13000000999",1),"会员ID");assertEquals(204,byPhone.size());
    }

    @Test void bookExportMatchesCatalogTitlesAndFullSafeAttributesBeyondTheFirstHundred() throws Exception {
        var file=export("books","keyword="+KEY,1);var rows=data(file,"书目ID");
        assertEquals(204,rows.size());assertEquals(listIds("/catalog-books","keyword="+KEY),csvIds(rows));
        List<String> first=find(rows,BOOK);assertEquals("0000000000000",first.get(1));
        assertEquals(KEY+"-登记书名-0",first.get(2));assertEquals(KEY+"-展示书名-0",first.get(3));assertEquals("adopted-special-0",first.get(4));
        assertEquals(0,new BigDecimal(first.get(10)).compareTo(BigDecimal.ZERO));assertEquals("0",first.get(11));assertEquals("BOARD",first.get(18));assertEquals("AGE_0_2 | AGE_3_4",first.get(19));
        assertEquals("\\N",find(rows,BOOK+1).get(10));assertEquals("",find(rows,BOOK+1).get(6));
        assertEquals("\\N",find(rows,BOOK).get(6));
        assertFalse(file.getContentAsString(StandardCharsets.UTF_8).contains("privateJson"));
    }

    @Test void allCatalogFiltersIncludingRepeatedAgeLabelsAreAppliedTogether() throws Exception {
        String query="keyword="+KEY+"&categoryId="+CATEGORY+"&status=0&bindingCode=BOARD&bindingState=KNOWN&ageState=KNOWN&ageLabels=AGE_0_2&ageLabels=AGE_3_4";
        var rows=data(export("books",query,1),"书目ID");assertEquals(51,rows.size());
        assertEquals(listIds("/catalog-books",query),csvIds(rows));
    }

    @ParameterizedTest @ValueSource(strings={"bindingState=UNKNOWN","ageState=UNKNOWN","bindingCode=HARDCOVER","ageLabels=AGE_7_9"})
    void knownAndUnknownCatalogFiltersMatchTheLiveList(String filter) throws Exception {
        String query="keyword="+KEY+"&"+filter;
        assertEquals(listIds("/catalog-books",query),csvIds(data(export("books",query,1),"书目ID")));
    }

    @Test void catalogSearchUsesDisplayAdoptedAndExactStoredBarcode() throws Exception {
        for(String keyword:List.of("adopted-special-0",KEY+"-展示书名-0",BARCODE)) {
            var query=mvc.perform(get("/management-exports/books").param("keyword",keyword).header("Authorization",bearer(1))).andExpect(status().isOk()).andReturn().getResponse();
            assertEquals(List.of(BOOK),csvIds(data(query,"书目ID")));
        }
        for(String keyword:List.of("000aa","000Aa")) {
            var query=mvc.perform(get("/management-exports/books").param("keyword",keyword).header("Authorization",bearer(1))).andExpect(status().isOk()).andReturn().getResponse();
            assertTrue(data(query,"书目ID").isEmpty());
        }
    }

    @Test void inventoryIncludesDownBooksAndAllRowsDespiteTheOldListTieOrdering() throws Exception {
        var rows=data(export("inventory","keyword="+KEY,1),"书目ID");assertEquals(204,rows.size());
        assertEquals(new HashSet<>(inventoryListIds("keyword="+KEY)),new HashSet<>(csvIds(rows)));
        List<Long> sorted=csvIds(rows);for(int i=1;i<sorted.size();i++)assertTrue(sorted.get(i-1)>sorted.get(i));
        assertEquals(102,rows.stream().filter(r->r.get(9).equals("0")).count());
        assertEquals("0",find(rows,BOOK).get(7));assertEquals("0",find(rows,BOOK).get(8));
    }

    @Test void inventoryCategoryStatusAndKeywordMatchTheActualGetBookPage() throws Exception {
        String query="keyword="+KEY+"&categoryId="+(CATEGORY+1)+"&status=1";
        assertEquals(new HashSet<>(inventoryListIds(query)),new HashSet<>(csvIds(data(export("inventory",query,1),"书目ID"))));
        var file=export("inventory",query,1);assertTrue(file.getContentAsString(StandardCharsets.UTF_8).contains("每行单位种，库存列单位册"));
    }

    @ParameterizedTest @ValueSource(strings={"members","books","inventory"})
    void emptyResultHasMetadataAndHeaderAndNoRows(String kind) throws Exception {
        var response=export(kind,"keyword=definitely-absent-export",1);
        assertEquals("0",response.getHeader("X-Total-Count"));assertTrue(data(response,kind.equals("members")?"会员ID":"书目ID").isEmpty());
    }

    @Test void responseHasBomAsOfBusinessDateUnitsAndDefensiveCsvValues() throws Exception {
        jdbc.update("UPDATE sys_user SET real_name=? WHERE id=?","=HYPERLINK(\"bad\",\"test\")\r\n第二行,\"原文\"",MEMBER);
        var response=export("members","keyword="+KEY,1);byte[] bytes=response.getContentAsByteArray();
        assertArrayEquals(new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf},Arrays.copyOf(bytes,3));
        assertNotNull(Instant.parse(response.getHeader("X-As-Of")));
        assertTrue(response.getHeader("Content-Disposition").matches("attachment; filename=\"management-members-[0-9]{8}T[0-9]{6}Z.csv\""));
        String text=response.getContentAsString(StandardCharsets.UTF_8);assertTrue(text.contains(response.getHeader("X-As-Of")));
        assertTrue(text.contains(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()));assertTrue(text.contains("\"单位\",\"人\""));
        assertEquals("'=HYPERLINK(\"bad\",\"test\")\r\n第二行,\"原文\"",find(data(response,"会员ID"),MEMBER).get(2));
        assertEquals("\\N",find(data(response,"会员ID"),MEMBER+1).get(15));
    }

    @Test void unknownDuplicateAndPageParametersAreRejectedByEveryRoute() throws Exception {
        for(String kind:List.of("members","books","inventory"))for(String query:List.of("page=1","size=10","unknown=x","keyword=a&keyword=b"))
            mvc.perform(get("/management-exports/"+kind+"?"+query).header("Authorization",bearer(1))).andExpect(status().isBadRequest());
        for(String suffix:List.of("members?accountStatus=2","members?serviceStatus=bad","books?bindingState=bad","books?ageLabels=AGE_0_2&ageLabels=AGE_0_2",
                "books?ageState=UNKNOWN&ageLabels=AGE_0_2","books?status=1&status=0","inventory?categoryId=-1","inventory?status=2"))
            mvc.perform(get("/management-exports/"+suffix).header("Authorization",bearer(1))).andExpect(status().isBadRequest());
    }

    @ParameterizedTest @ValueSource(strings={"members","books","inventory"})
    void oversizeResultIsRejectedBeforeAnyTruncatedFileCanBeDownloaded(String kind) throws Exception {
        List<Object[]> data=new ArrayList<>();
        for(int i=0;i<20001;i++)data.add(new Object[]{200000+i,"bulk-"+i});
        if(kind.equals("members"))jdbc.batchUpdate("INSERT INTO sys_user(id,username,password,role,status,deleted) VALUES (?,?,'synthetic-only','READER',1,0)",data);
        else jdbc.batchUpdate("INSERT INTO book(id,isbn,title,stock_total,stock_available,status,deleted) VALUES (?,?,'oversize-export',0,0,1,0)",data);
        var response=mvc.perform(get("/management-exports/"+kind).param("keyword",kind.equals("members")?"bulk-":"oversize-export").header("Authorization",bearer(1)))
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        assertNull(response.getHeader("Content-Disposition"));assertNull(response.getHeader("X-Total-Count"));
        assertFalse(response.getContentType().contains("csv"));assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("20000"));
    }

    @ParameterizedTest @ValueSource(longs={2,3})
    void compatibilityRolesCannotExportAdministratorFiles(long actor) throws Exception {
        for(String kind:List.of("members","books","inventory"))mvc.perform(get("/management-exports/"+kind).header("Authorization",bearer(actor))).andExpect(status().isForbidden());
    }

    @Test void anonymousCannotExportAndAllSuccessfulRejectedReadsPreserveBusinessRows() throws Exception {
        var before=snapshot();
        for(String kind:List.of("members","books","inventory")) {
            mvc.perform(get("/management-exports/"+kind)).andExpect(status().isUnauthorized());
            export(kind,"keyword="+KEY,1);
            mvc.perform(get("/management-exports/"+kind+"?page=1").header("Authorization",bearer(1))).andExpect(status().isBadRequest());
        }
        assertEquals(before,snapshot());
        var tx=ManagementExportService.class.getAnnotation(Transactional.class);assertTrue(tx.readOnly());assertEquals(Isolation.REPEATABLE_READ,tx.isolation());
    }

    protected String bearer(long actor){User u=users.selectById(actor);return "Bearer "+tokens.generateToken(u.getId(),u.getUsername(),u.getRole(),u.getTokenVersion());}
    protected MockHttpServletResponse export(String kind,String query,long actor)throws Exception{return mvc.perform(get("/management-exports/"+kind+"?"+query).header("Authorization",bearer(actor))).andExpect(status().isOk()).andReturn().getResponse();}
    private List<Long> listIds(String path,String query)throws Exception{List<Long> ids=new ArrayList<>();for(int p=1;;p++){var response=mvc.perform(get(path+"?"+query+"&page="+p+"&size=100").header("Authorization",bearer(1))).andExpect(status().isOk()).andReturn().getResponse();JsonNode page=json.readTree(response.getContentAsByteArray()).path("data");page.path("records").forEach(r->ids.add(r.path("id").asLong()));if(ids.size()>=page.path("total").asLong())return ids;}}
    private List<Long> inventoryListIds(String query)throws Exception{var response=mvc.perform(get("/books?"+query+"&page=1&size=20001").header("Authorization",bearer(1))).andExpect(status().isOk()).andReturn().getResponse();JsonNode value=json.readTree(response.getContentAsByteArray()).path("data");assertEquals(value.path("total").asInt(),value.path("records").size());List<Long> ids=new ArrayList<>();value.path("records").forEach(r->ids.add(r.path("id").asLong()));assertEquals(ids.size(),new HashSet<>(ids).size());return ids;}
    private List<Long> csvIds(List<List<String>> rows){return rows.stream().map(r->Long.parseLong(r.get(0))).toList();}
    private List<String> find(List<List<String>> rows,long id){return rows.stream().filter(r->r.get(0).equals(Long.toString(id))).findFirst().orElseThrow();}
    private List<List<String>> data(MockHttpServletResponse response,String header)throws Exception{var rows=parse(response.getContentAsString(StandardCharsets.UTF_8));for(int i=0;i<rows.size();i++)if(rows.get(i).get(0).equals(header))return rows.subList(i+1,rows.size());throw new AssertionError("Missing CSV header");}
    private List<List<String>> parse(String raw){String text=raw.startsWith("\uFEFF")?raw.substring(1):raw;List<List<String>> out=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false;for(int i=0;i<text.length();i++){char c=text.charAt(i);if(c=='"'){if(quoted&&i+1<text.length()&&text.charAt(i+1)=='"'){cell.append('"');i++;}else quoted=!quoted;}else if(!quoted&&c==','){row.add(cell.toString());cell.setLength(0);}else if(!quoted&&c=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n'){row.add(cell.toString());cell.setLength(0);out.add(row);row=new ArrayList<>();i++;}else cell.append(c);}assertFalse(quoted);return out;}
    private Map<String,String> snapshot()throws Exception{Map<String,String> out=new TreeMap<>();for(String table:List.of("sys_user","member_profile","member_child","book","book_copy","catalog_book_attribute","catalog_book_age_label","legacy_record")){String order=table.equals("member_profile")?"user_id":table.equals("catalog_book_attribute")?"book_id":table.equals("catalog_book_age_label")?"book_id,label_code":"id";out.put(table,json.writeValueAsString(jdbc.queryForList("SELECT * FROM "+table+" ORDER BY "+order)));}return out;}
}
