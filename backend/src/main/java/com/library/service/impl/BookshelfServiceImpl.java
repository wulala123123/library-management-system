package com.library.service.impl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.library.common.PageResult;
import com.library.entity.*;
import com.library.mapper.*;
import com.library.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
@Service @RequiredArgsConstructor
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class BookshelfServiceImpl implements BookshelfService {
 private final BookshelfMapper shelves;private final BookshelfStoreyMapper storeys;private final BookLocationMapper locations;
 private final LocationMaintenanceService writes;private final LocationAddressService addresses;private final JdbcTemplate jdbc;
 public PageResult<Bookshelf> getPage(int page,int size,String keyword,Long deptId){int p=Math.max(1,page),s=Math.max(1,Math.min(100,size));var q=new LambdaQueryWrapper<Bookshelf>();if(keyword!=null&&!keyword.isEmpty())q.and(w->w.like(Bookshelf::getName,keyword).or().like(Bookshelf::getCode,keyword));if(deptId!=null)q.eq(Bookshelf::getDeptId,deptId);q.orderByDesc(Bookshelf::getCreateTime);var result=shelves.selectPage(new Page<Bookshelf>(p,s),q);Map<Long,String> names=new HashMap<>();var ids=result.getRecords().stream().map(Bookshelf::getDeptId).filter(Objects::nonNull).distinct().toList();if(!ids.isEmpty())jdbc.query("SELECT id,name FROM sys_dept WHERE id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",r->{names.put(r.getLong(1),r.getString(2));},ids.toArray());result.getRecords().forEach(b->b.setDeptName(names.get(b.getDeptId())));return new PageResult<>(result.getRecords(),result.getTotal(),(long)p,(long)s);}
 public Bookshelf getById(Long id){var b=shelves.selectById(id);if(b==null)throw LocationInput.failure(404,"LOCATION_NOT_FOUND","书架不存在");if(b.getDeptId()!=null){var n=jdbc.queryForList("SELECT name FROM sys_dept WHERE id=?",String.class,b.getDeptId());if(!n.isEmpty())b.setDeptName(n.get(0));}return b;}
 public List<BookshelfStorey> getStoreysByBookshelfId(Long id){var rows=storeys.selectList(new LambdaQueryWrapper<BookshelfStorey>().eq(BookshelfStorey::getBookshelfId,id).orderByAsc(BookshelfStorey::getLevelNum));if(rows.isEmpty())return rows;var ids=rows.stream().map(BookshelfStorey::getId).toList();var children=locations.selectList(new LambdaQueryWrapper<BookLocation>().in(BookLocation::getStoreyId,ids).orderByAsc(BookLocation::getCode));decorate(children);Map<Long,List<BookLocation>> grouped=new HashMap<>();children.forEach(c->grouped.computeIfAbsent(c.getStoreyId(),k->new ArrayList<>()).add(c));rows.forEach(r->r.setLocations(grouped.getOrDefault(r.getId(),List.of())));return rows;}
 public PageResult<BookLocation> getLocations(int page,int size,Long storey,String status){int p=Math.max(1,page),s=Math.max(1,Math.min(100,size));var q=new LambdaQueryWrapper<BookLocation>();if(storey!=null)q.eq(BookLocation::getStoreyId,storey);if(status!=null&&!status.isEmpty())q.eq(BookLocation::getStatus,status);q.orderByAsc(BookLocation::getCode);var result=locations.selectPage(new Page<BookLocation>(p,s),q);decorate(result.getRecords());return new PageResult<>(result.getRecords(),result.getTotal(),(long)p,(long)s);}
 private void decorate(List<BookLocation> rows){var map=addresses.find(rows.stream().map(BookLocation::getId).toList());Map<Long,Map<String,Object>> books=new HashMap<>();var ids=rows.stream().map(BookLocation::getBookId).filter(Objects::nonNull).distinct().toList();if(!ids.isEmpty())jdbc.query("SELECT id,title,cover_image FROM book WHERE id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",r->{Map<String,Object> data=new HashMap<>();data.put("title",r.getString(2));data.put("cover",r.getString(3));books.put(r.getLong(1),data);},ids.toArray());for(var row:rows){var a=map.get(row.getId());row.setAddress(a.address());row.setLegacyBinding(a.legacyBinding());row.setLinkedCopyCount(a.linkedCopyCount());var b=books.get(row.getBookId());if(b!=null){row.setBookTitle((String)b.get("title"));row.setCoverImage((String)b.get("cover"));}}}
 @Transactional public Bookshelf create(JsonNode b,Long actor){return getById(writes.save("BOOKSHELF",true,b,actor));}
 @Transactional public Bookshelf update(JsonNode b,Long actor){return getById(writes.save("BOOKSHELF",false,b,actor));}
 @Transactional public void delete(Long id,JsonNode b,Long actor){writes.delete("BOOKSHELF",id,b,actor);}
 @Transactional public BookshelfStorey createStorey(JsonNode b,Long actor){return storeys.selectById(writes.save("STOREY",true,b,actor));}
 @Transactional public BookshelfStorey updateStorey(JsonNode b,Long actor){return storeys.selectById(writes.save("STOREY",false,b,actor));}
 @Transactional public void deleteStorey(Long id,JsonNode b,Long actor){writes.delete("STOREY",id,b,actor);}
 @Transactional public BookLocation createLocation(JsonNode b,Long actor){return location(writes.save("LOCATION",true,b,actor));}
 @Transactional public BookLocation updateLocation(JsonNode b,Long actor){return location(writes.save("LOCATION",false,b,actor));}
 private BookLocation location(Long id){var row=locations.selectById(id);decorate(List.of(row));return row;}
 @Transactional public void deleteLocation(Long id,JsonNode b,Long actor){writes.delete("LOCATION",id,b,actor);}
 @Transactional public void assignBookToLocation(Long id,Long book,JsonNode b,Long actor){writes.binding(id,book,false,b,actor);}
 @Transactional public void clearLocation(Long id,JsonNode b,Long actor){writes.binding(id,null,true,b,actor);}
}