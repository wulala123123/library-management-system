package com.library.catalogattributes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.transaction.TransactionDefinition;
import java.util.*;
import static com.library.catalogattributes.CatalogAttributeSupport.*;

@Service
public class CatalogBookQuery {
    private final CatalogAttributeService attributes;
    public CatalogBookQuery(CatalogAttributeService attributes){this.attributes=attributes;}
    private static final String FROM=" FROM book b LEFT JOIN catalog_book_attribute a ON a.book_id=b.id LEFT JOIN book_category c ON c.id=b.category_id AND c.deleted=0";
    private static final String SELECT="SELECT b.*,c.name AS category_name,a.display_title_override,a.adopted_source_title,a.binding_code";
    static String one(MultiValueMap<String,String> q,String k){List<String> a=q.get(k);if(a==null)return null;if(a.size()!=1)invalid();return a.get(0);}
    static int integer(String s,int fallback){if(s==null)return fallback;if(!s.matches("-?[0-9]+"))invalid();try{return Integer.parseInt(s);}catch(NumberFormatException e){invalid();return 0;}}
    static Long positive(String s){if(s==null)return null;if(!s.matches("[0-9]+"))invalid();try{long n=Long.parseLong(s);if(n<=0)invalid();return n;}catch(NumberFormatException e){invalid();return null;}}
    static String allowed(String s,List<String> options){if(s!=null&&!options.contains(s))invalid();return s;}
    public JsonNode page(MultiValueMap<String,String> q){
        if(!Set.of("page","size","keyword","categoryId","status","bindingCode","bindingState","ageLabels","ageState").containsAll(q.keySet()))invalid();
        int page=Math.max(1,integer(one(q,"page"),1)),size=Math.max(1,Math.min(100,integer(one(q,"size"),10)));
        String keyword=one(q,"keyword"),binding=allowed(one(q,"bindingCode"),BINDINGS),bindingState=allowed(one(q,"bindingState"),List.of("KNOWN","UNKNOWN")),ageState=allowed(one(q,"ageState"),List.of("KNOWN","UNKNOWN"));
        Long category=positive(one(q,"categoryId"));String status=allowed(one(q,"status"),List.of("0","1"));
        List<String> ages=q.getOrDefault("ageLabels",List.of());if(ages.size()>5||new HashSet<>(ages).size()!=ages.size()||!AGES.containsAll(ages))invalid();
        if("UNKNOWN".equals(bindingState)&&binding!=null||"UNKNOWN".equals(ageState)&&!ages.isEmpty())invalid();
        if(keyword!=null){if(keyword.codePointCount(0,keyword.length())>200)invalid();for(int i=0;i<keyword.length();){char c=keyword.charAt(i);if(Character.isSurrogate(c)&&(!Character.isHighSurrogate(c)||i+1>=keyword.length()||!Character.isLowSurrogate(keyword.charAt(i+1))))invalid();int cp=keyword.codePointAt(i);if(Character.isISOControl(cp))invalid();i+=Character.charCount(cp);}}
        StringBuilder where=new StringBuilder(" WHERE b.deleted=0");List<Object> args=new ArrayList<>();
        if(category!=null){where.append(" AND b.category_id=?");args.add(category);}if(status!=null){where.append(" AND b.status=?");args.add(Integer.valueOf(status));}
        if(binding!=null){where.append(" AND a.binding_code=?");args.add(binding);}if(bindingState!=null)where.append(" AND a.binding_code IS "+(bindingState.equals("KNOWN")?"NOT ":"")+"NULL");
        if(ageState!=null)where.append(ageState.equals("KNOWN")?" AND a.age_known=1":" AND (a.age_known IS NULL OR a.age_known=0)");
        if(!ages.isEmpty()){where.append(" AND EXISTS(SELECT 1 FROM catalog_book_age_label age WHERE age.book_id=b.id AND age.label_code IN ("+placeholders(ages.size())+"))");args.addAll(ages);}
        if(keyword!=null&&!keyword.isEmpty()){String like="%"+keyword.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
            where.append(" AND (b.title LIKE ? ESCAPE '!' OR a.display_title_override LIKE ? ESCAPE '!' OR a.adopted_source_title LIKE ? ESCAPE '!' OR b.author LIKE ? ESCAPE '!' OR b.isbn LIKE ? ESCAPE '!' OR EXISTS(SELECT 1 FROM book_copy copy WHERE copy.book_id=b.id AND copy.barcode_key=?))");for(int i=0;i<5;i++)args.add(like);args.add(bytes(keyword));}
        return attributes.tx(TransactionDefinition.ISOLATION_REPEATABLE_READ,true,()->{long total=attributes.jdbc.queryForObject("SELECT COUNT(*)"+FROM+where,Long.class,args.toArray());List<Object> paged=new ArrayList<>(args);paged.add(size);paged.add((long)(page-1)*size);var rows=attributes.jdbc.queryForList(SELECT+FROM+where+" ORDER BY b.create_time DESC,b.id DESC LIMIT ? OFFSET ?",paged.toArray());return CatalogAttributeService.page(convert(rows),total,page,size);});
    }
    public JsonNode get(long id){if(id<=0)invalid();return attributes.tx(TransactionDefinition.ISOLATION_REPEATABLE_READ,true,()->{var rows=attributes.jdbc.queryForList(SELECT+FROM+" WHERE b.id=? AND b.deleted=0",id);if(rows.isEmpty())throw fail(404,"BOOK_NOT_FOUND");return convert(rows).get(0);});}
    private List<JsonNode> convert(List<Map<String,Object>> rows){Map<Long,List<String>> labels=new HashMap<>();if(!rows.isEmpty()){Object[] ids=rows.stream().map(r->r.get("id")).toArray();for(var r:attributes.jdbc.queryForList("SELECT book_id,label_code FROM catalog_book_age_label WHERE book_id IN ("+placeholders(ids.length)+")",ids))labels.computeIfAbsent(num(r.get("book_id")),x->new ArrayList<>()).add((String)r.get("label_code"));}
        List<JsonNode> result=new ArrayList<>();for(var r:rows){long id=num(r.get("id"));ObjectNode n=obj("id",id,"isbn",r.get("isbn"),"catalogTitle",r.get("title"),"displayTitle",r.get("display_title_override")==null?r.get("title"):r.get("display_title_override"),"adoptedSourceTitle",r.get("adopted_source_title"));
            String[] fields={"author","publisher","publishDate","categoryId","categoryName","price","pages","coverImage","description","location","stockTotal","stockAvailable","status","createTime","updateTime","bindingCode"};String[] cols={"author","publisher","publish_date","category_id","category_name","price","pages","cover_image","description","location","stock_total","stock_available","status","create_time","update_time","binding_code"};
            for(int i=0;i<fields.length;i++){Object value=r.get(cols[i]);if(value instanceof java.sql.Date d)value=d.toLocalDate().toString();else if(value instanceof java.time.LocalDate d)value=d.toString();else if(value instanceof java.sql.Timestamp t)value=t.toLocalDateTime().toString();else if(value instanceof java.time.LocalDateTime t)value=t.toString();n.set(fields[i],tree(value));}
            List<String> a=labels.get(id);n.set("ageLabels",a==null?NullNode.instance:tree(AGES.stream().filter(a::contains).toList()));result.add(n);
        }return result;
    }
}
