package com.library.catalogsets;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.*;
import static com.library.catalogattributes.CatalogAttributeSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure synthetic cases and frozen V15 SQL; no production snapshot. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:catalog-source-repair;DB_CLOSE_DELAY=-1;MODE=MYSQL","mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"})
public class CatalogSetSourceQueryRegressionTest extends CatalogSetServiceTest {
    static String legacyCte(CatalogSetSourceQuery source) {
String raw=source.rawSql();String marker="CASE WHEN r.root_kind<>'OBJECT' THEN 'WRONG_TYPE' WHEN r.present=0 THEN 'MISSING' WHEN r.value_kind='NULL' THEN 'NULL' WHEN r.value_kind<>'STRING' THEN 'WRONG_TYPE' WHEN CHAR_LENGTH(r.raw_value)=0 THEN 'EMPTY' WHEN REGEXP_LIKE(r.raw_value,'"+source.whitespacePattern()+"') THEN 'WHITESPACE' WHEN "+source.exact("r.raw_value","'暂无设置'")+" THEN 'UNSET' WHEN "+source.exact("r.raw_value","'套装中单本'")+" THEN 'MEMBER_MARKER' ELSE 'UNMAPPED' END";
        return "WITH raw_source AS ("+raw+"), matches AS (SELECT c.barcode_key,COUNT(*) AS match_count,MIN(c.book_id) AS copy_book,SUM(CASE WHEN b.id IS NULL OR b.deleted<>0 THEN 1 ELSE 0 END) AS invalid_count FROM book_copy c LEFT JOIN book b ON b.id=c.book_id GROUP BY c.barcode_key), classified AS (SELECT r.*,"+marker+" AS marker,CONCAT(r.root_kind,':',r.present,':',CASE WHEN r.present=0 THEN 'MISSING' WHEN r.value_kind='NULL' THEN 'NULL' WHEN r.value_kind='STRING' THEN 'STRING' ELSE 'NON_STRING' END,':',COALESCE("+source.sha("r.cell_json")+",'missing')) AS fingerprint,CASE WHEN m.match_count=1 AND m.copy_book=r.book_id AND r.source_deleted=0 AND m.invalid_count=0 THEN 0 ELSE 1 END AS invalid_association FROM raw_source r LEFT JOIN matches m ON m.barcode_key="+source.bin("r.source_key")+"), linked_books AS (SELECT b.id AS case_book,r.id AS case_record FROM raw_source r JOIN book b ON b.id=r.book_id UNION SELECT b.id AS case_book,r.id AS case_record FROM raw_source r JOIN book_copy c ON c.barcode_key="+source.bin("r.source_key")+" JOIN book b ON b.id=c.book_id), links AS (SELECT case_book,case_record FROM linked_books UNION ALL SELECT NULL AS case_book,r.id AS case_record FROM raw_source r WHERE NOT EXISTS(SELECT 1 FROM book b WHERE b.id=r.book_id) AND NOT EXISTS(SELECT 1 FROM book_copy c JOIN book b ON b.id=c.book_id WHERE c.barcode_key="+source.bin("r.source_key")+")), subjects AS (SELECT b.id AS case_book,CAST(NULL AS "+(source.mysql?"SIGNED":"BIGINT")+") AS orphan_id FROM book b UNION ALL SELECT NULL,case_record FROM links WHERE case_book IS NULL), aggregated AS (SELECT s.case_book,s.orphan_id,COUNT(r.id) AS source_count,COUNT(DISTINCT r.fingerprint) AS variants,MIN(r.marker) AS single_marker,COALESCE(MAX(r.invalid_association),0) AS invalid_association,COALESCE(MAX(CASE WHEN r.marker='MEMBER_MARKER' THEN 1 ELSE 0 END),0) AS has_marker,COALESCE(MAX(CASE WHEN r.marker='UNMAPPED' THEN 1 ELSE 0 END),0) AS has_unmapped,COALESCE(MAX(CASE WHEN r.marker IN ('MISSING','NULL','EMPTY','WHITESPACE','UNSET','WRONG_TYPE') THEN 1 ELSE 0 END),0) AS has_incomplete FROM subjects s LEFT JOIN links l ON (s.case_book=l.case_book OR s.case_book IS NULL AND l.case_book IS NULL AND s.orphan_id=l.case_record) LEFT JOIN classified r ON r.id=l.case_record GROUP BY s.case_book,s.orphan_id), relationships AS (SELECT m.book_id,COUNT(*) AS history_count,SUM(CASE WHEN m.status='ACTIVE' AND s.status='ACTIVE' THEN 1 ELSE 0 END) AS active_count FROM catalog_set_member m JOIN catalog_set s ON s.id=m.set_id GROUP BY m.book_id), cases AS (SELECT g.*,"+CatalogSetService.BOOK_COLUMNS+",CASE WHEN g.source_count=0 THEN 'NO_SOURCE' WHEN g.variants>1 THEN 'CONFLICT' ELSE g.single_marker END AS marker_state,CASE WHEN g.source_count=0 THEN 'NO_SOURCE' WHEN g.invalid_association>0 THEN 'ASSOCIATION_INVALID' ELSE 'VALID' END AS association_state,COALESCE(rel.history_count,0) AS history_count,COALESCE(rel.active_count,0) AS active_count,CASE WHEN rel.active_count>0 THEN 'ACTIVE' WHEN rel.history_count>0 THEN 'HISTORICAL' ELSE 'NONE' END AS relationship_state FROM aggregated g LEFT JOIN book b ON b.id=g.case_book LEFT JOIN catalog_book_attribute a ON a.book_id=b.id LEFT JOIN relationships rel ON rel.book_id=g.case_book) ";

    }
    static void assertEquivalentRows(List<Map<String,Object>> expected,List<Map<String,Object>> actual) {
        assertEquals(expected.size(),actual.size());
        Set<String> promotions=new TreeSet<>();
        for(int i=0;i<expected.size();i++) {
            assertEquals(expected.get(i).keySet(),actual.get(i).keySet());
            for(String key:expected.get(i).keySet()) {
                Object a=expected.get(i).get(key),b=actual.get(i).get(key);
                if(a instanceof Number&&b instanceof Number&&!a.getClass().equals(b.getClass()))promotions.add(key+":"+a.getClass().getSimpleName()+"->"+b.getClass().getSimpleName());
                // MySQL UNION/COALESCE may widen a JDBC aggregate integer;
                // compare every numeric value exactly, without string coercion.
                boolean same=a instanceof Number&&b instanceof Number
                    ?new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString()))==0
                    :Objects.deepEquals(a,b);
                assertTrue(same,"case column differs: row="+i+" column="+key+" oldType="+(a==null?"NULL":a.getClass().getSimpleName())+" newType="+(b==null?"NULL":b.getClass().getSimpleName()));
            }
        }
        System.out.println("B34_SOURCE_JDBC_NUMERIC_PROMOTIONS "+promotions);
    }
    @Test void preaggregationPreservesAllCaseColumnsAndStatementLocalSourceDigest() {
        List<String> cells=List.of("{}","{\"套装类型\":null}","{\"套装类型\":\"\"}","{\"套装类型\":\"　\"}","{\"套装类型\":\"暂无设置\"}","{\"套装类型\":\"套装中单本\"}","{\"套装类型\":123}","{\"套装类型\":{\"a\":1,\"longKey\":false}}","[]","broken","{\"套装类型\":\"synthetic unmapped\"}");
        List<Long> ids=new ArrayList<>();
        for(int i=0;i<85;i++){long id=book();ids.add(id);source(id,sourceCopy(id),cells.get(i%cells.size()));}
        long a=ids.get(0),b=ids.get(1),empty=book();
        jdbc.update("UPDATE book SET deleted=1 WHERE id=?",ids.get(2));
        source(a,sourceCopy(b),"{\"套装类型\":\"套装中单本\"}");
        source(a,"000SYNTHETIC-"+UUID.randomUUID(),"{\"套装类型\":\"synthetic unmapped\"}");
        long collision=book();
        while(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_record WHERE id=?",Integer.class,collision)!=0)collision=book();
        jdbc.update("INSERT INTO legacy_record(id,source_system,record_type,source_key,book_id,details_json,display_name) VALUES (?,'huiben','BOOK',?,NULL,?,'合成B34来源')",collision,"SYNTHETIC-ORPHAN-"+collision,"{\"套装类型\":\"synthetic unmapped\"}");
        long s=set(),t=set();confirm(preview(members("ADD_MEMBERS",s,newMember(a))));confirm(preview(members("ADD_MEMBERS",t,newMember(a))));confirm(preview(operation("RETIRE_SET",t)));
        String ordered="SELECT * FROM cases ORDER BY CASE WHEN case_book IS NULL THEN 1 ELSE 0 END,case_book,orphan_id";
        assertEquivalentRows(jdbc.queryForList(legacyCte(service.sources)+ordered),jdbc.queryForList(service.sources.cte()+ordered));
        assertEquals(0,jdbc.queryForObject(service.sources.cte()+"SELECT source_count FROM cases WHERE case_book=?",Integer.class,empty));
        assertEquals(1,jdbc.queryForObject(service.sources.cte()+"SELECT COUNT(*) FROM cases WHERE case_book IS NULL AND orphan_id=?",Integer.class,collision));
        assertEquals(1,jdbc.queryForObject(service.sources.cte()+"SELECT COUNT(*) FROM cases WHERE case_book=?",Integer.class,collision));
        jdbc.update("UPDATE legacy_record SET details_json=? WHERE book_id=?","{\"套装类型\":\"synthetic unmapped\",\"unrelated\":\""+"x".repeat(40000)+"\"}",a);
        JsonNode expanded=context(a).path("source");
        jdbc.update("UPDATE legacy_record SET details_json=? WHERE book_id=?","{\"套装类型\":\"synthetic unmapped\"}",a);
        assertEquals(expanded.path("digest"),context(a).path("source").path("digest"));
        JsonNode page=service.reviews(q("scope","ALL","bookDeleted","ALL","reason","UNMAPPED_MARKER","size","37"),admin);
        assertTrue(page.path("total").asLong()>0);assertTrue(page.path("records").size()<=37);assertFalse(page.toString().contains("unrelated"));
        assertFalse(service.sources.cte().contains("s.case_book=l.case_book OR"));assertTrue(service.sources.cte().contains("SELECT DISTINCT projected.*"));
    }
}
