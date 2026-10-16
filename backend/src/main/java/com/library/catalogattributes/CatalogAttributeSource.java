package com.library.catalogattributes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static com.library.catalogattributes.CatalogAttributeSupport.*;
import static com.library.catalogattributes.CatalogAttributeHistory.*;

/** Exactly six constant JSON paths. No SELECT returns details_json itself. */
final class CatalogAttributeSource {
    private final JdbcTemplate jdbc;
    private final boolean mysql;
    private static final LinkedHashMap<String,String> KEYS=new LinkedHashMap<>();
    static {KEYS.put("ageLabels","适读年龄");KEYS.put("bindingCode","装帧");KEYS.put("displayTitleOverride","自定义书名");KEYS.put("adoptedSourceTitle","书籍名称");KEYS.put("publisher","出版社");KEYS.put("setType","套装类型");}
    CatalogAttributeSource(JdbcTemplate j){jdbc=j;try(var c=Objects.requireNonNull(j.getDataSource()).getConnection()){mysql=c.getMetaData().getDatabaseProductName().equals("MySQL");}catch(Exception e){throw fail(500,"CATALOG_SAVE_FAILED");}}
    String binary(String x){return "CAST("+x+" AS "+(mysql?"BINARY":"VARBINARY")+")";}
    String where(){return binary("l.source_system")+"=? AND "+binary("l.record_type")+"=? AND (l.book_id=? OR EXISTS(SELECT 1 FROM book_copy relevant WHERE relevant.book_id=? AND relevant.barcode_key="+binary("l.source_key")+"))";}
    Object[] args(long id){return new Object[]{bytes("huiben"),bytes("BOOK"),id,id};}
    ObjectNode read(long bookId,JsonNode profile,boolean lock){
        List<Long> locked=null;
        if(lock){locked=jdbc.queryForList("SELECT l.id FROM legacy_record l WHERE "+where()+" ORDER BY l.id LIMIT 201 FOR UPDATE",Long.class,args(bookId));if(locked.size()>200)throw conflict("SOURCE_LIMIT_EXCEEDED");}
        StringBuilder sql=new StringBuilder("SELECT l.id,l.source_key,l.book_id,b.deleted AS source_deleted,");
        sql.append(mysql?"SHA2(CAST(l.details_json AS BINARY),256)":"LOWER(RAWTOHEX(HASH('SHA-256',STRINGTOUTF8(l.details_json))))").append(" AS details_sha");
        String doc="CASE WHEN JSON_VALID(l.details_json) THEN l.details_json ELSE '{}' END";
        sql.append(",CASE WHEN JSON_VALID(l.details_json) THEN JSON_TYPE(l.details_json) ELSE 'INVALID' END AS root_kind");
        int index=0;
        for(String key:KEYS.values()){String path="'$.\""+key+"\"'";sql.append(",JSON_CONTAINS_PATH(").append(doc).append(",'one',").append(path).append(") AS present_").append(index).append(",JSON_EXTRACT(").append(doc).append(',').append(path).append(") AS value_").append(index++);}
        sql.append(" FROM legacy_record l LEFT JOIN book b ON b.id=l.book_id WHERE ").append(where()).append(" ORDER BY l.id LIMIT 201");
        List<Map<String,Object>> rows=jdbc.queryForList(sql.toString(),args(bookId));
        if(rows.size()>200)throw conflict("SOURCE_LIMIT_EXCEEDED");
        if(lock&&!locked.equals(rows.stream().map(r->num(r.get("id"))).toList()))throw conflict("SOURCE_CHANGED");
        List<Map<String,Object>> copies=new ArrayList<>();
        if(!rows.isEmpty()){List<Object> keys=rows.stream().map(r->(Object)bytes((String)r.get("source_key"))).toList();copies=jdbc.queryForList("SELECT c.id,c.book_id,c.barcode_key,b.deleted FROM book_copy c LEFT JOIN book b ON b.id=c.book_id WHERE c.barcode_key IN ("+placeholders(keys.size())+") ORDER BY c.id",keys.toArray());}
        ArrayNode records=JSON.createArrayNode(),fingerprints=JSON.createArrayNode();boolean valid=true;
        Map<String,List<JsonNode>> allCells=new LinkedHashMap<>();KEYS.keySet().forEach(k->allCells.put(k,new ArrayList<>()));
        for(var row:rows){
            String sourceKey=(String)row.get("source_key");byte[] keyBytes=bytes(sourceKey);
            List<Map<String,Object>> matches=copies.stream().filter(c->Arrays.equals((byte[])c.get("barcode_key"),keyBytes)).toList();
            Map<String,Object> copy=matches.size()==1?matches.get(0):null;
            Long sourceBook=nullable(row.get("book_id")),copyBook=copy==null?null:nullable(copy.get("book_id"));
            boolean rowValid=copy!=null&&Objects.equals(sourceBook,copyBook)&&row.get("source_deleted")!=null&&num(row.get("source_deleted"))==0&&copy.get("deleted")!=null&&num(copy.get("deleted"))==0;
            valid &= rowValid;
            ObjectNode cells=JSON.createObjectNode();int i=0;
            for(String field:KEYS.keySet()){
                boolean present=Boolean.TRUE.equals(row.get("present_"+i))||row.get("present_"+i) instanceof Number n&&n.intValue()==1;
                JsonNode value=present?readJsonValue(row.get("value_"+i)):NullNode.instance;
                String kind=!present?"MISSING":value.isNull()?"NULL":value.isTextual()?"STRING":"NON_STRING";
                String raw=value.isTextual()&&value.textValue().codePointCount(0,value.textValue().length())<=2000?value.textValue():null;
                ObjectNode cell=obj("kind",kind,"rawValue",raw,"valueSha256",hash(obj("present",present,"value",value)));
                // A non-object root cannot masquerade as absent valid field evidence.
                if(!"OBJECT".equals(row.get("root_kind")))cell.put("kind","NON_STRING").set("rawValue",NullNode.instance);
                cells.set(field,cell);allCells.get(field).add(cell);i++;
            }
            Long copyId=copy==null?null:num(copy.get("id"));
            records.add(obj("recordId",num(row.get("id")),"sourceKey",sourceKey,"copyId",copyId,"bookId",sourceBook,"detailsSha256",row.get("details_sha"),"cells",cells));
            fingerprints.add(obj("recordId",num(row.get("id")),"sourceKeyHex",HexFormat.of().withUpperCase().formatHex(keyBytes),"sourceBookId",sourceBook,"copyId",copyId,"copyBookId",copyBook,
                    "sourceBookDeleted",row.get("source_deleted"),"copyBookDeleted",copy==null?null:copy.get("deleted"),"detailsSha256",row.get("details_sha"),"cells",cells));
        }
        String association=rows.isEmpty()?"NO_SOURCE":valid?"VALID":"ASSOCIATION_INVALID";
        ObjectNode fields=JSON.createObjectNode();for(String field:KEYS.keySet())fields.set(field,field(field,allCells.get(field),valid));
        return obj("bookId",bookId,"catalogDigest",catalogDigest(profile),"revision",profile.path("revision").longValue(),"sourceDigest",hash(obj("schemaVersion",1,"parserVersion","huiben-book-attributes-v1","bookId",bookId,"records",fingerprints)),
                "associationState",association,"sourceCount",rows.size(),"fields",fields,"records",records);
    }
    private JsonNode readJsonValue(Object raw){if(raw==null)return NullNode.instance;return CatalogAttributeSupport.read(raw instanceof byte[] b?new String(b,java.nio.charset.StandardCharsets.UTF_8):raw.toString());}
    static ObjectNode field(String field,List<JsonNode> cells,boolean associationValid){
        if(cells.isEmpty())return state("NO_SOURCE",null,false);
        if(cells.stream().map(c->c.path("kind").asText()+":"+c.path("valueSha256").asText()).distinct().count()!=1)return state("CONFLICT",null,false);
        JsonNode c=cells.get(0);String kind=c.path("kind").asText();
        if(kind.equals("MISSING"))return state("MISSING",null,false);if(kind.equals("NULL"))return state("NULL",null,false);if(kind.equals("NON_STRING"))return state("WRONG_TYPE",null,false);
        if(c.get("rawValue").isNull())return state("UNMAPPED",null,false);
        String s=c.path("rawValue").textValue();if(s.isEmpty())return state("EMPTY",null,false);if(s.codePoints().allMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)))return state("WHITESPACE",null,false);
        if((field.equals("ageLabels")||field.equals("bindingCode"))&&s.equals("暂无设置"))return state("UNSET",null,false);
        Object proposed=s;
        switch(field){case "ageLabels"->{Set<String> codes=new HashSet<>();for(String token:s.split(",",-1)){int n=AGE_TEXT.indexOf(token);if(n<0)return state("UNMAPPED",null,false);codes.add(AGES.get(n));}proposed=AGES.stream().filter(codes::contains).toList();}
            case "bindingCode"->{int n=List.of("纸板书","平装","精装").indexOf(s);if(n<0)return state("UNMAPPED",null,false);proposed=BINDINGS.get(n);}
            case "displayTitleOverride","adoptedSourceTitle"->{if(!validText(s,200))return state("UNMAPPED",null,false);}
            default->{return state("VALUE",s,false);}}
        return state("VALUE",proposed,associationValid);
    }
    private static ObjectNode state(String state,Object value,boolean canAdopt){return obj("state",state,"canAdopt",canAdopt,"proposedValue",value);}
}
