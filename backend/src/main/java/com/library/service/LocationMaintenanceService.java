package com.library.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.library.common.PageResult;
import com.library.dto.LocationContracts.StructureEvent;
import com.library.mapper.BookMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.library.service.LocationInput.*;

@Service @RequiredArgsConstructor
public class LocationMaintenanceService {
    private final JdbcTemplate jdbc;private final BookMapper books;private final ObjectMapper json;private final LocationAddressService addresses;
    private static String table(String type){return switch(type){case "BOOKSHELF"->"bookshelf";case "STOREY"->"bookshelf_storey";case "LOCATION"->"book_location";default->throw failure(400,"INVALID_INPUT","对象类型无效");};}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public long save(String type,boolean create,JsonNode body,Long actor){
        String required=switch(type){case "BOOKSHELF"->"name,code,deptId,location,capacity,status,reason";case "STOREY"->"bookshelfId,name,levelNum,capacity,status,reason";case "LOCATION"->"storeyId,code,status,reason";default->throw failure(400,"INVALID_INPUT","对象类型无效");};
        keys(body,create?required:required+",id,expectedVersion",!create&&type.equals("LOCATION")?"bookId":"");String reason=text(body.get("reason"),500,true);long id=create?0:number(body.get("id"),true);
        Map<String,Object> fields=new LinkedHashMap<>();
        if(!type.equals("LOCATION")){fields.put("name",text(body.get("name"),100,true));fields.put("capacity",integer(body.get("capacity"),1,99999));fields.put("status",integer(body.get("status"),0,1));}
        if(!type.equals("STOREY")){String code=text(body.get("code"),50,true);if(!code.matches("[A-Za-z0-9_-]+"))invalid();fields.put("code",code);}
        if(type.equals("BOOKSHELF")){fields.put("dept_id",body.get("deptId").isNull()?null:number(body.get("deptId"),true));fields.put("location",body.get("location").isNull()?null:text(body.get("location"),200,false));}
        if(type.equals("STOREY")){fields.put("bookshelf_id",number(body.get("bookshelfId"),true));fields.put("level_num",integer(body.get("levelNum"),Integer.MIN_VALUE,Integer.MAX_VALUE));}
        if(type.equals("LOCATION")){fields.put("storey_id",number(body.get("storeyId"),true));fields.put("status",create?word(body.get("status"),"EMPTY","DISABLED"):word(body.get("status"),"EMPTY","OCCUPIED","DISABLED"));}
        books.lockCatalog();Map<String,Object> before=create?null:read(type,id,true);if(!create)version(before,number(body.get("expectedVersion"),false));
        Long requestedBook=body.has("bookId")&&!body.get("bookId").isNull()?number(body.get("bookId"),true):null;
        if(requestedBook!=null){if(books.lockById(requestedBook)==null)throw failure(404,"BOOK_NOT_FOUND","书目不存在");}
        lockChain(type,id,before,fields);
        if(type.equals("LOCATION")&&!create){
            if(body.has("bookId")){Long old=nullable(before.get("book_id"));if(!Objects.equals(old,requestedBook)){if(requestedBook==null){String next=clearStatus(before);if(!next.equals(fields.get("status")))throw failure(409,"LOCATION_UNAVAILABLE","解除绑定不能隐式切换地址状态");fields.put("book_id",null);}else{assignable(before,requestedBook);if(!"OCCUPIED".equals(fields.get("status")))invalid();fields.put("book_id",requestedBook);}}}
            Long finalBook=fields.containsKey("book_id")?nullable(fields.get("book_id")):nullable(before.get("book_id"));
            if("OCCUPIED".equals(fields.get("status"))&&finalBook==null&&!"OCCUPIED".equals(before.get("status")))throw failure(409,"LOCATION_UNAVAILABLE","无旧绑定不能伪造占用状态");
        }
        if(create){String names=String.join(",",fields.keySet());id=insert("INSERT INTO "+table(type)+" ("+names+") VALUES ("+String.join(",",Collections.nCopies(fields.size(),"?"))+")",fields.values().toArray());append(type,id,"CREATE",actor,reason,null,read(type,id,false));return id;}
        update(type,id,before,fields,"UPDATE",actor,reason);return id;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void binding(Long id,Long book,boolean clear,JsonNode body,Long actor){
        keys(body,"expectedVersion,reason","");long expected=number(body.get("expectedVersion"),false);String reason=text(body.get("reason"),500,true);if(id==null||id<=0||!clear&&(book==null||book<=0))invalid();
        books.lockCatalog();Map<String,Object> before=read("LOCATION",id,true);version(before,expected);if(!clear&&books.lockById(book)==null)throw failure(404,"BOOK_NOT_FOUND","书目不存在");lockChain("LOCATION",id,before,Map.of());
        Map<String,Object> fields=new LinkedHashMap<>();if(clear){fields.put("book_id",null);fields.put("status",clearStatus(before));}else{assignable(before,book);fields.put("book_id",book);fields.put("status","OCCUPIED");}
        update("LOCATION",id,before,fields,clear?"CLEAR":"ASSIGN",actor,reason);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void delete(String type,Long id,JsonNode body,Long actor){
        keys(body,"expectedVersion,reason","");if(id==null||id<=0)invalid();long expected=number(body.get("expectedVersion"),false);String reason=text(body.get("reason"),500,true);books.lockCatalog();Map<String,Object> before=read(type,id,true);version(before,expected);lockChain(type,id,before,Map.of());
        List<Long> locations;
        if(type.equals("BOOKSHELF")){if(count("SELECT COUNT(*) FROM bookshelf_storey WHERE bookshelf_id=? AND deleted=0",id)>0)inUse();locations=jdbc.queryForList("SELECT l.id FROM book_location l JOIN bookshelf_storey s ON s.id=l.storey_id WHERE s.bookshelf_id=?",Long.class,id);}
        else if(type.equals("STOREY")){if(count("SELECT COUNT(*) FROM book_location WHERE storey_id=? AND deleted=0",id)>0)inUse();locations=jdbc.queryForList("SELECT id FROM book_location WHERE storey_id=?",Long.class,id);}else locations=List.of(id);
        for(Long location:locations){Map<String,Object> row=read("LOCATION",location,false);if(row.get("book_id")!=null||"OCCUPIED".equals(row.get("status"))||count("SELECT COUNT(*) FROM book_copy WHERE registered_location_id=?",location)>0||count("SELECT COUNT(*) FROM book_copy_action_location WHERE before_location_id=? OR after_location_id=?",location,location)>0)inUse();}
        update(type,id,before,Map.of("deleted",1),"DELETE",actor,reason);
    }
    private void assignable(Map<String,Object> row,Long book){
        if(Objects.equals(nullable(row.get("book_id")),book)&&"OCCUPIED".equals(row.get("status")))return;
        if(row.get("book_id")!=null||"OCCUPIED".equals(row.get("status")))throw failure(409,"LOCATION_IN_USE","请先明确解除原书目绑定");
        if(!addresses.get(num(row.get("id"))).address().selectable())throw failure(409,"LOCATION_UNAVAILABLE","地址不可用");
    }
    private String clearStatus(Map<String,Object> row){String status=(String)row.get("status");if("DISABLED".equals(status))return status;if("EMPTY".equals(status)||"OCCUPIED".equals(status))return "EMPTY";throw failure(409,"LOCATION_UNAVAILABLE","原库位状态未知，请单独核实编辑");}
    private void lockChain(String type,long id,Map<String,Object> before,Map<String,Object> next){
        Set<Long> shelves=new TreeSet<>(),storeys=new TreeSet<>(),locations=new TreeSet<>();
        if(type.equals("BOOKSHELF")){if(id>0)shelves.add(id);}else if(type.equals("STOREY")){if(before!=null)shelves.add(num(before.get("bookshelf_id")));if(next.containsKey("bookshelf_id")){long target=num(next.get("bookshelf_id"));read("BOOKSHELF",target,true);shelves.add(target);}if(id>0)storeys.add(id);}
        else{if(before!=null)storeys.add(num(before.get("storey_id")));if(next.containsKey("storey_id")){long target=num(next.get("storey_id"));Map<String,Object> s=read("STOREY",target,true);read("BOOKSHELF",num(s.get("bookshelf_id")),true);storeys.add(target);}for(long storey:storeys){var rows=jdbc.queryForList("SELECT bookshelf_id FROM bookshelf_storey WHERE id=?",storey);if(!rows.isEmpty())shelves.add(num(rows.get(0).get("bookshelf_id")));}if(id>0)locations.add(id);}
        addresses.lock("bookshelf",shelves);addresses.lock("bookshelf_storey",storeys);addresses.lock("book_location",locations);
    }
    private void update(String type,long id,Map<String,Object> before,Map<String,Object> fields,String operation,Long actor,String reason){
        boolean changed=fields.entrySet().stream().anyMatch(e->!same(e.getValue(),before.get(e.getKey())));if(!changed)return;long version=num(before.get("layout_version"));if(version==Long.MAX_VALUE)throw failure(409,"LOCATION_STALE","地址版本超出范围");
        List<Object> args=new ArrayList<>(fields.values());args.add(id);args.add(version);
        if(jdbc.update("UPDATE "+table(type)+" SET "+String.join(",",fields.keySet().stream().map(k->k+"=?").toList())+",layout_version=layout_version+1,update_time=CURRENT_TIMESTAMP WHERE id=? AND layout_version=?",args.toArray())!=1)throw failure(409,"LOCATION_STALE","地址版本已变化");
        Map<String,Object> after=read(type,id,false);for(var e:fields.entrySet())if(!same(e.getValue(),after.get(e.getKey())))throw failure(500,"ACTION_SAVE_FAILED","地址修改未完成");append(type,id,operation,actor,reason,before,after);
    }
    private void append(String type,long id,String operation,Long actor,String reason,Map<String,Object> before,Map<String,Object> after){
        if(actor==null||actor<=0)throw failure(403,"ACTION_ROLE_REQUIRED","操作者无效");jdbc.update("INSERT INTO location_structure_event(entity_type,entity_id,operation,operator_id,occurred_at,reason,before_version,after_version,before_json,after_json,snapshot_version) VALUES (?,?,?,?,?,?,?,?,?,?,1)",type,id,operation,actor,LocalDateTime.ofInstant(Instant.now(),ZoneOffset.UTC),reason,before==null?null:before.get("layout_version"),after.get("layout_version"),before==null?null:write(snapshot(before)),write(snapshot(after)));
    }
    private ObjectNode snapshot(Map<String,Object> row){ObjectNode node=json.createObjectNode();for(var e:row.entrySet()){String[] words=e.getKey().toLowerCase(Locale.ROOT).split("_");StringBuilder name=new StringBuilder(words[0]);for(int i=1;i<words.length;i++)name.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));Object value=e.getValue();if(value instanceof java.sql.Timestamp||value instanceof java.time.temporal.TemporalAccessor)node.put(name.toString(),value.toString());else node.set(name.toString(),json.valueToTree(value));}return node;}
    private Map<String,Object> read(String type,long id,boolean live){var rows=jdbc.queryForList("SELECT * FROM "+table(type)+" WHERE id=?"+(live?" AND deleted=0":""),id);if(rows.isEmpty())throw failure(404,"LOCATION_NOT_FOUND","地址或父级不存在");return rows.get(0);}
    private void version(Map<String,Object> row,long expected){if(num(row.get("layout_version"))!=expected)throw failure(409,"LOCATION_STALE","地址版本已变化，请重读");}
    private boolean same(Object a,Object b){return a instanceof Number x&&b instanceof Number y?x.longValue()==y.longValue():Objects.equals(a,b);}
    private static long num(Object n){return ((Number)n).longValue();}private static Long nullable(Object n){return n==null?null:num(n);}
    private long count(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}
    private void inUse(){throw failure(409,"LOCATION_IN_USE","地址、子项、旧绑定或位置历史仍被引用");}
    private long insert(String sql,Object[] args){GeneratedKeyHolder key=new GeneratedKeyHolder();jdbc.update(c->{var p=c.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},key);return Objects.requireNonNull(key.getKey()).longValue();}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw failure(500,"ACTION_SAVE_FAILED","地址审计保存失败");}}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResult<StructureEvent> events(String type,Long id,int page,int size){table(type);if(id==null||id<=0)invalid();long p=Math.max(1,page);int s=Math.max(1,Math.min(100,size));long total=count("SELECT COUNT(*) FROM location_structure_event WHERE entity_type=? AND entity_id=?",type,id);return new PageResult<>(jdbc.query("SELECT * FROM location_structure_event WHERE entity_type=? AND entity_id=? ORDER BY id DESC LIMIT ? OFFSET ?",this::mapEvent,type,id,s,(p-1)*s),total,p,(long)s);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public StructureEvent event(Long id){if(id==null||id<=0)invalid();var rows=jdbc.query("SELECT * FROM location_structure_event WHERE id=?",this::mapEvent,id);if(rows.isEmpty())throw failure(404,"LOCATION_NOT_FOUND","地址事件不存在");return rows.get(0);}
    private StructureEvent mapEvent(ResultSet r,int i)throws SQLException{try{return new StructureEvent(r.getLong("id"),r.getString("entity_type"),r.getLong("entity_id"),r.getString("operation"),r.getLong("operator_id"),r.getObject("occurred_at",LocalDateTime.class).toInstant(ZoneOffset.UTC).toString(),r.getString("reason"),r.getObject("before_version",Long.class),r.getLong("after_version"),r.getInt("snapshot_version"),r.getString("before_json")==null?null:json.readTree(r.getString("before_json")),json.readTree(r.getString("after_json")));}catch(Exception e){throw new SQLException("Invalid structure audit",e);}}
}
