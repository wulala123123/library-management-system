package com.library.service;

import com.library.common.PageResult;
import com.library.dto.LocationContracts.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.sql.*;
import java.util.*;
import static com.library.service.LocationInput.*;

@Service @RequiredArgsConstructor
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class LocationAddressService {
    private final JdbcTemplate jdbc;
    private static final String FROM=" FROM book_location l LEFT JOIN bookshelf_storey s ON s.id=l.storey_id LEFT JOIN bookshelf b ON b.id=s.bookshelf_id ";
    private static final String SELECT="SELECT l.id,l.layout_version,l.code,l.storey_id,l.book_id,l.status,l.deleted,s.id AS actual_storey,s.layout_version AS sv,s.name AS sn,s.level_num,s.status AS ss,s.deleted AS sd,s.bookshelf_id,b.id AS actual_shelf,b.layout_version AS bv,b.code AS bc,b.name AS bn,b.location AS bl,b.status AS bs,b.deleted AS bd,(SELECT COUNT(*) FROM book_copy c WHERE c.registered_location_id=l.id) AS linked";
    public PageResult<AddressRow> page(int page,int size,String keyword,Long shelf,Long storey,boolean selectableOnly){
        if(shelf!=null&&shelf<=0||storey!=null&&storey<=0||keyword!=null&&keyword.length()>200)invalid();int p=Math.max(1,page),n=Math.max(1,Math.min(100,size));
        String where=" WHERE l.deleted=0";List<Object> args=new ArrayList<>();
        if(shelf!=null){where+=" AND s.bookshelf_id=?";args.add(shelf);}if(storey!=null){where+=" AND l.storey_id=?";args.add(storey);}
        if(keyword!=null&&!keyword.isEmpty()){where+=" AND (l.code LIKE ? ESCAPE '!' OR b.code LIKE ? ESCAPE '!' OR b.name LIKE ? ESCAPE '!' OR s.name LIKE ? ESCAPE '!')";String like="%"+keyword.replace("!","!!").replace("%","!%").replace("_","!_")+"%";for(int i=0;i<4;i++)args.add(like);}
        if(selectableOnly)where+=" AND s.deleted=0 AND b.deleted=0 AND s.status=1 AND b.status=1 AND ("+new CopyInventoryProjection(jdbc).exact("l.status","EMPTY")+" OR "+new CopyInventoryProjection(jdbc).exact("l.status","OCCUPIED")+")";
        Long total=jdbc.queryForObject("SELECT COUNT(*)"+FROM+where,Long.class,args.toArray());args.add(n);args.add((long)(p-1)*n);
        return new PageResult<>(jdbc.query(SELECT+FROM+where+" ORDER BY l.id LIMIT ? OFFSET ?",this::row,args.toArray()),total,(long)p,(long)n);
    }
    public AddressRow get(Long id){if(id==null||id<=0)invalid();AddressRow r=find(List.of(id)).get(id);if(r==null)throw failure(404,"LOCATION_NOT_FOUND","库位不存在");return r;}
    public Map<Long,AddressRow> find(Collection<Long> ids){Map<Long,AddressRow> result=new LinkedHashMap<>();if(ids.isEmpty())return result;jdbc.query(SELECT+FROM+" WHERE l.id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY l.id",rs->{AddressRow row=row(rs,0);result.put(row.address().locationId(),row);},ids.toArray());return result;}
    private AddressRow row(ResultSet r,int ordinal)throws SQLException{
        String status=r.getString("status"),reason=null;
        if(r.getInt("deleted")!=0||r.getObject("actual_storey")==null||r.getObject("actual_shelf")==null||r.getInt("sd")!=0||r.getInt("bd")!=0)reason="LOCATION_REFERENCE_ANOMALY";
        else if(r.getInt("ss")!=1||r.getInt("bs")!=1||"DISABLED".equals(status))reason="LOCATION_DISABLED";
        else if(!"EMPTY".equals(status)&&!"OCCUPIED".equals(status))reason="LOCATION_STATUS_UNKNOWN";
        AddressSnapshot a=new AddressSnapshot(r.getLong("id"),r.getLong("layout_version"),r.getString("code"),r.getObject("storey_id",Long.class),r.getObject("sv",Long.class),r.getString("sn"),r.getObject("level_num",Integer.class),r.getObject("bookshelf_id",Long.class),r.getObject("bv",Long.class),r.getString("bc"),r.getString("bn"),r.getString("bl"),reason==null,reason);
        Long book=r.getObject("book_id",Long.class);return new AddressRow(a,new LegacyBinding(book,status,book!=null||"OCCUPIED".equals(status)||!List.of("EMPTY","DISABLED").contains(status==null?"":status)),r.getLong("linked"));
    }
    /** Called only under inventory_mutex, after book/copy locks. Lock whole parent sets before child sets. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void lockAddresses(Collection<Long> ids){
        if(ids.isEmpty())return;Map<Long,AddressRow> rows=find(ids);Set<Long> shelves=new TreeSet<>(),storeys=new TreeSet<>();
        for(var row:rows.values()){if(row.address().bookshelfId()!=null)shelves.add(row.address().bookshelfId());if(row.address().storeyId()!=null)storeys.add(row.address().storeyId());}
        lock("bookshelf",shelves);lock("bookshelf_storey",storeys);lock("book_location",new TreeSet<>(ids));
    }
    public void lock(String table,Collection<Long> ids){for(Long id:ids)jdbc.queryForList("SELECT id FROM "+table+" WHERE id=? FOR UPDATE",id);}
    public static AddressSnapshot missing(Long id){return new AddressSnapshot(id,null,null,null,null,null,null,null,null,null,null,null,false,"LOCATION_REFERENCE_ANOMALY");}
}
