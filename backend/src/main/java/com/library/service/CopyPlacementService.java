package com.library.service;

import com.fasterxml.jackson.databind.*;
import com.library.dto.LocationContracts.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service @RequiredArgsConstructor
public class CopyPlacementService {
    private final JdbcTemplate jdbc;private final LocationAddressService addresses;private final ObjectMapper json;
    public Map<Long,CopyPlacement> find(Collection<Long> ids){
        Map<Long,CopyPlacement> result=new LinkedHashMap<>();if(ids.isEmpty())return result;
        var rows=jdbc.queryForList("SELECT c.id,c.book_id,c.location,c.action_version,c.registered_location_id,c.last_location_action_id,a.copy_id AS source_copy,a.book_id AS source_book,a.action_type,a.after_version,a.before_version,a.after_location,r.schema_version,r.state AS request_state,r.preview_json,r.receipt_json,x.event_id,x.before_source_action_id,x.source_behavior,x.snapshot_version,x.before_json,x.after_json,x.after_location_id FROM book_copy c LEFT JOIN book_copy_action a ON a.id=c.last_location_action_id LEFT JOIN book_copy_action_request r ON r.id=a.request_id LEFT JOIN book_copy_action_location x ON x.event_id=a.id WHERE c.id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY c.id",ids.toArray());
        Set<Long> locations=new TreeSet<>();for(var row:rows)if(row.get("registered_location_id")!=null)locations.add(num(row.get("registered_location_id")));var map=addresses.find(locations);
        for(var r:rows){Long location=nullable(r.get("registered_location_id")),source=nullable(r.get("last_location_action_id"));String raw=(String)r.get("location");AddressSnapshot address=location==null?null:map.containsKey(location)?map.get(location).address():LocationAddressService.missing(location);
            Placement placement=placement(location,raw,address);boolean anomaly=location!=null&&"LOCATION_REFERENCE_ANOMALY".equals(address.unavailableReason());
            anomaly|=source==null?location!=null:!validSource(r,location,source,raw);
            result.put(num(r.get("id")),new CopyPlacement(placement,source,anomaly));
        }return result;
    }
    public CopyPlacement get(Long id){CopyPlacement row=find(List.of(id)).get(id);if(row==null)throw LocationInput.failure(404,"COPY_NOT_FOUND","实体册不存在");return row;}
    public static Placement placement(Long id,String raw,AddressSnapshot address){return id!=null?new Placement("REGISTERED",null,address):raw==null||raw.trim().isEmpty()?new Placement("UNASSIGNED",null,null):new Placement("TEXT",raw,null);}
    public static PlacementSnapshot snapshot(CopyPlacement current,String raw){return new PlacementSnapshot(current.placement(),raw);}
    private boolean validSource(Map<String,Object> r,Long location,Long source,String raw){
        try{
            if(!Objects.equals(nullable(r.get("source_copy")),nullable(r.get("id")))||!Objects.equals(nullable(r.get("source_book")),nullable(r.get("book_id"))))return false;
            if(!"COMMITTED".equals(r.get("request_state"))||!List.of("MOVE","RESTORE").contains(r.get("action_type")))return false;
            long after=num(r.get("after_version"));if(after>num(r.get("action_version"))||after<=num(r.get("before_version"))||!Objects.equals(raw,r.get("after_location")))return false;
            JsonNode plan=json.readTree((String)r.get("preview_json")),receipt=json.readTree((String)r.get("receipt_json"));JsonNode pc=copy(plan,num(r.get("id"))),rc=copy(receipt,num(r.get("id")));
            if(pc==null||rc==null||rc.path("eventId").asLong(-1)!=source||pc.path("afterVersion").asLong(-1)!=after||rc.path("afterVersion").asLong(-1)!=after||!Objects.equals(value(pc.get("afterLocation")),raw)||!Objects.equals(value(rc.get("afterLocation")),raw))return false;
            int schema=((Number)r.get("schema_version")).intValue();
            if(!Objects.equals(plan.path("action").asText(),r.get("action_type"))||!Objects.equals(receipt.path("action").asText(),r.get("action_type"))||receipt.path("schemaVersion").asInt()!=schema||pc.path("bookId").asLong(-1)!=num(r.get("book_id"))||rc.path("bookId").asLong(-1)!=num(r.get("book_id")))return false;
            if(schema==1)return location==null&&r.get("event_id")==null;
            if(schema!=2||r.get("event_id")==null||!"THIS_EVENT".equals(r.get("source_behavior"))||num(r.get("snapshot_version"))!=1)return false;
            JsonNode before=json.readTree((String)r.get("before_json")),next=json.readTree((String)r.get("after_json"));
            if(!before.equals(pc.get("beforePlacement"))||!next.equals(pc.get("afterPlacement"))||!before.equals(rc.get("beforePlacement"))||!next.equals(rc.get("afterPlacement")))return false;
            if(!"THIS_EVENT".equals(pc.path("locationSourceBehavior").asText())||!"THIS_EVENT".equals(rc.path("locationSourceBehavior").asText())||rc.path("afterLocationActionId").asLong(-1)!=source)return false;
            Long prior=nullable(r.get("before_source_action_id"));if(!Objects.equals(prior,number(pc.get("beforeLocationActionId")))||!Objects.equals(prior,number(rc.get("beforeLocationActionId"))))return false;
            if(!Objects.equals(location,nullable(r.get("after_location_id")))||!Objects.equals(raw,value(next.get("rawText"))))return false;
            JsonNode placement=next.path("placement");Long snapshotId=placement.path("kind").asText().equals("REGISTERED")?number(placement.path("registeredLocation").get("locationId")):null;
            // An explicit new MOVE/RESTORE establishes a healthy source even if its preserved before-source was anomalous.
            return Objects.equals(location,snapshotId);
        }catch(Exception ignored){return false;}
    }
    private JsonNode copy(JsonNode node,long id){for(JsonNode c:node.path("copies"))if(c.path("copyId").asLong(-1)==id)return c;return null;}
    private String value(JsonNode node){return node==null||node.isNull()?null:node.textValue();}
    private Long number(JsonNode node){return node==null||node.isNull()?null:node.isIntegralNumber()?node.longValue():Long.MIN_VALUE;}
    private static Long nullable(Object o){return o==null?null:num(o);}private static long num(Object o){return ((Number)o).longValue();}
}
