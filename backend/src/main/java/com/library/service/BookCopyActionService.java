package com.library.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.library.dto.BookCopyActionV2Contracts;
import com.library.dto.BookCopyActionV2Contracts.PlanV2;
import com.library.dto.LocationContracts.*;
import com.library.dto.BookCopyActionContracts.*;
import com.library.entity.Book;
import com.library.entity.BookCopy;
import com.library.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service @RequiredArgsConstructor
public class BookCopyActionService {
    private final JdbcTemplate jdbc; private final BookMapper books; private final BookCopyMapper copies;
    private final BookCopyActionMapper events; private final CopyInventoryProjection projection;
    private final CopyPlacementService placements; private final LocationAddressService addresses;
    private final IntakeCatalog catalog; private final BookCopyQueryService queries; private final ObjectMapper json;
    private static final List<String> ACTIONS=List.of("MOVE","DISABLE","ENABLE","WRITE_OFF","RESTORE");
    public record Plan(String action,List<CopyChange> copies,List<BookChange> books,List<String> warnings,Map<Long,String> metadataDigests) {}
    private record Row(Long id,Long owner,String action,String hash,String digest,String state,int schema,Instant expires,String request,String plan,String receipt){}

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public View preview(PreviewRequest request,Long actor,boolean admin){
        validate(request);role(request.getAction(),admin);books.lockCatalog();Row row=byKeyRow(request.getIdempotencyKey(),true);String hash=catalog.digest(request);
        if(row!=null){owner(row,actor);if(row.schema()!=1||!hash.equals(row.hash()))throw failure(409,"IDEMPOTENCY_KEY_REUSED","该办理键已用于其他输入，请查询原办理",row.id(),null,null);return view(row);}
        Plan p=build(request);String digest=catalog.digest(p);Instant expires=Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(15,ChronoUnit.MINUTES);
        Long id=insert("INSERT INTO book_copy_action_request(idempotency_key,operator_id,request_hash,preview_digest,schema_version,action_type,request_json,preview_json,state,expires_at) VALUES (?,?,?,?,1,?,?,?,'PREVIEWED',?)",bytes(request.getIdempotencyKey()),actor,hash,digest,p.action(),write(request),write(p),local(expires));
        return new View(id,"PREVIEWED",1,p.action(),digest,expires.toString(),p.copies(),p.books(),p.warnings(),null);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt confirm(Long id,ConfirmRequest request,Long actor,boolean admin){
        if(id==null||id<=0||request==null)invalid();String key=key(request.getIdempotencyKey());if(request.getPreviewDigest()==null||!request.getPreviewDigest().matches("[a-f0-9]{64}"))invalid();
        books.lockCatalog();Row row=byIdRow(id,true);owner(row,actor);role(row.action(),admin);
        Row keyed=byKeyRow(key,false);if(keyed==null||!keyed.id().equals(id)||!row.digest().equals(request.getPreviewDigest()))throw failure(409,"IDEMPOTENCY_KEY_REUSED","办理键或摘要不一致，请查询原办理",id,null,null);
        if(row.schema()!=1)throw failure(409,"LOCATION_CONTRACT_UPGRADE_REQUIRED","请使用对应版本办理",id,null,null);
        if(row.state().equals("COMMITTED"))return read(row.receipt(),Receipt.class);
        if(!Instant.now().isBefore(row.expires()))throw failure(409,"PREVIEW_EXPIRED","预览已过期，请重新核对",id,null,null);
        PreviewRequest original=read(row.request(),PreviewRequest.class);Plan current;
        try{current=build(original);}catch(BookCopyActionException e){throw failure(409,"PREVIEW_STALE","册状态、来源或库存已变化，请重新预览",id,e.copyId(),e.ordinal());}
        if(!row.digest().equals(catalog.digest(current)))throw failure(409,"PREVIEW_STALE","册或书目已变化，请重新预览",id,null,null);
        Instant now=Instant.now().truncatedTo(ChronoUnit.SECONDS);List<CopyReceipt> receipts=new ArrayList<>();
        for(CopyChange c:current.copies()){
            Long flow=null;if(!row.action().equals("MOVE"))flow=insert("INSERT INTO stock_flow(book_id,flow_type,quantity,related_id,operator_id,remark) VALUES (?,?,1,NULL,?,?)",c.bookId(),"COPY_"+row.action(),actor,original.getReason());
            Long event=events.append(id,row.action(),original.getReason(),actor,local(now),flow,c);
            Long stateAction=row.action().equals("MOVE")?c.beforeStateActionId():event;
            if(jdbc.update("UPDATE book_copy SET status=?,location=?,action_version=?,last_state_action_id=? WHERE id=? AND action_version=? AND "+projection.binary("status")+"=?",c.afterStatus(),c.afterLocation(),c.afterVersion(),stateAction,c.copyId(),c.beforeVersion(),bytes(c.beforeStatus()))!=1)throw failure(409,"PREVIEW_STALE","册版本已变化",id,c.copyId(),c.ordinal());
            if(row.action().equals("MOVE")||row.action().equals("RESTORE"))jdbc.update("UPDATE book_copy SET registered_location_id=NULL,last_location_action_id=? WHERE id=?",event,c.copyId());
            receipts.add(new CopyReceipt(event,flow,c));
        }
        for(BookChange b:current.books())if((b.deltaTotal()!=0||b.deltaAvailable()!=0)&&jdbc.update("UPDATE book SET stock_total=stock_total+?,stock_available=stock_available+?,update_time=CURRENT_TIMESTAMP WHERE id=? AND deleted=0 AND stock_total=? AND stock_available=?",b.deltaTotal(),b.deltaAvailable(),b.bookId(),b.stockTotalBefore(),b.stockAvailableBefore())!=1)throw failure(409,"PREVIEW_STALE","汇总库存已变化",id,null,null);
        Receipt receipt=new Receipt(id,1,key,row.action(),actor,now.toString(),receipts,current.books(),current.warnings());
        if(jdbc.update("UPDATE book_copy_action_request SET state='COMMITTED',committed_at=?,receipt_json=? WHERE id=? AND state='PREVIEWED'",local(now),write(receipt),id)!=1)throw failure(500,"ACTION_SAVE_FAILED","动作保存失败，请查询原办理",id,null,null);
        return receipt;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public View get(Long id,Long actor){if(id==null||id<=0)invalid();Row r=byIdRow(id,false);owner(r,actor);return view(r);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public View byKey(String value,Long actor){Row r=byKeyRow(key(value),false);owner(r,actor);return view(r);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Object getAny(Long id,Long actor){if(id==null||id<=0)invalid();Row row=byIdRow(id,false);owner(row,actor);return viewAny(row);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Object byKeyAny(String value,Long actor){Row row=byKeyRow(key(value),false);owner(row,actor);return viewAny(row);}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Object confirmAny(Long id,ConfirmRequest input,Long actor,boolean admin){
        if(id==null||id<=0)invalid();books.lockCatalog();Row row=byIdRow(id,true);owner(row,actor);
        if(row.schema()==1)return confirm(id,input,actor,admin);
        if(row.schema()!=2)throw failure(409,"LOCATION_CONTRACT_UPGRADE_REQUIRED","不支持此办理版本",id,null,null);
        if(input==null)invalid();String key=key(input.getIdempotencyKey());role(row.action(),admin);
        if(input.getPreviewDigest()==null||!input.getPreviewDigest().matches("[a-f0-9]{64}"))invalid();
        Row keyed=byKeyRow(key,false);if(keyed==null||!keyed.id().equals(id)||!row.digest().equals(input.getPreviewDigest()))throw failure(409,"IDEMPOTENCY_KEY_REUSED","办理键或摘要不一致",id,null,null);
        if(row.state().equals("COMMITTED"))return read(row.receipt(),BookCopyActionV2Contracts.Receipt.class);
        if(!Instant.now().isBefore(row.expires()))throw failure(409,"PREVIEW_EXPIRED","预览已过期",id,null,null);
        PlanV2 plan;JsonNode request=read(row.request(),JsonNode.class);
        try{plan=buildV2(request);}catch(BookCopyActionException e){throw failure(409,"PREVIEW_STALE","册或地址已变化，请重新预览",id,e.copyId(),e.ordinal());}
        if(!row.digest().equals(catalog.digest(plan)))throw failure(409,"PREVIEW_STALE","册或地址已变化，请重新预览",id,null,null);
        Instant now=Instant.now().truncatedTo(ChronoUnit.SECONDS);List<BookCopyActionV2Contracts.CopyReceipt> output=new ArrayList<>();
        for(var c:plan.copies()){
            Long flow=null;if(!row.action().equals("MOVE"))flow=insert("INSERT INTO stock_flow(book_id,flow_type,quantity,related_id,operator_id,remark) VALUES (?,?,1,NULL,?,?)",c.bookId(),"COPY_"+row.action(),actor,request.path("reason").asText());
            CopyChange old=new CopyChange(c.ordinal(),c.copyId(),c.bookId(),c.barcode(),c.beforeStatus(),c.afterStatus(),c.beforeLocation(),c.afterLocation(),c.beforeVersion(),c.afterVersion(),c.beforeStateActionId(),c.restoreOfActionId(),c.deltaTotal(),c.deltaAvailable());
            Long event=events.append(id,row.action(),request.path("reason").asText(),actor,local(now),flow,old);
            Long afterSource=c.locationSourceBehavior().equals("THIS_EVENT")?event:c.beforeLocationActionId();Long target=placementId(c.afterPlacement());
            jdbc.update("INSERT INTO book_copy_action_location(event_id,before_location_id,after_location_id,before_source_action_id,source_behavior,snapshot_version,before_json,after_json) VALUES (?,?,?,?,?,1,?,?)",event,placementId(c.beforePlacement()),target,c.beforeLocationActionId(),c.locationSourceBehavior(),write(c.beforePlacement()),write(c.afterPlacement()));
            if(jdbc.update("UPDATE book_copy SET status=?,location=?,action_version=?,last_state_action_id=?,registered_location_id=?,last_location_action_id=? WHERE id=? AND action_version=?",c.afterStatus(),c.afterLocation(),c.afterVersion(),row.action().equals("MOVE")?c.beforeStateActionId():event,target,afterSource,c.copyId(),c.beforeVersion())!=1)throw failure(409,"PREVIEW_STALE","册版本已变化",id,c.copyId(),c.ordinal());
            ObjectNode receipt=json.valueToTree(c);receipt.put("eventId",event);if(flow==null)receipt.putNull("stockFlowId");else receipt.put("stockFlowId",flow);if(afterSource==null)receipt.putNull("afterLocationActionId");else receipt.put("afterLocationActionId",afterSource);output.add(read(write(receipt),BookCopyActionV2Contracts.CopyReceipt.class));
        }
        for(BookChange b:plan.books())if((b.deltaTotal()!=0||b.deltaAvailable()!=0)&&jdbc.update("UPDATE book SET stock_total=stock_total+?,stock_available=stock_available+?,update_time=CURRENT_TIMESTAMP WHERE id=? AND deleted=0 AND stock_total=? AND stock_available=?",b.deltaTotal(),b.deltaAvailable(),b.bookId(),b.stockTotalBefore(),b.stockAvailableBefore())!=1)throw failure(409,"PREVIEW_STALE","汇总库存已变化",id,null,null);
        var receipt=new BookCopyActionV2Contracts.Receipt(id,2,key,row.action(),actor,now.toString(),output,plan.books(),plan.warnings());
        if(jdbc.update("UPDATE book_copy_action_request SET state='COMMITTED',committed_at=?,receipt_json=? WHERE id=? AND state='PREVIEWED'",local(now),write(receipt),id)!=1)throw failure(500,"ACTION_SAVE_FAILED","动作保存失败",id,null,null);
        return receipt;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public BookCopyActionV2Contracts.View previewV2(JsonNode raw,Long actor,boolean admin){
        ObjectNode request=validateV2(raw);String action=request.path("action").asText();role(action,admin);books.lockCatalog();String hash=catalog.digest(request);Row existing=byKeyRow(request.path("idempotencyKey").asText(),true);
        if(existing!=null){owner(existing,actor);if(existing.schema()!=2||!hash.equals(existing.hash()))throw failure(409,"IDEMPOTENCY_KEY_REUSED","办理键已用于其他输入",existing.id(),null,null);return (BookCopyActionV2Contracts.View)viewAny(existing);}
        PlanV2 plan=buildV2(request);String digest=catalog.digest(plan);Instant expires=Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(15,ChronoUnit.MINUTES);
        Long id=insert("INSERT INTO book_copy_action_request(idempotency_key,operator_id,request_hash,preview_digest,schema_version,action_type,request_json,preview_json,state,expires_at) VALUES (?,?,?,?,2,?,?,?,'PREVIEWED',?)",bytes(request.path("idempotencyKey").asText()),actor,hash,digest,action,write(request),write(plan),local(expires));
        return new BookCopyActionV2Contracts.View(id,"PREVIEWED",2,action,digest,expires.toString(),plan.copies(),plan.books(),plan.warnings(),null);
    }
    private Object viewAny(Row row){if(row.schema()==1)return view(row);if(row.schema()!=2)throw failure(409,"LOCATION_CONTRACT_UPGRADE_REQUIRED","不支持此办理版本",row.id(),null,null);PlanV2 p=read(row.plan(),PlanV2.class);return new BookCopyActionV2Contracts.View(row.id(),row.state(),2,row.action(),row.digest(),row.expires().toString(),p.copies(),p.books(),p.warnings(),row.receipt()==null?null:read(row.receipt(),BookCopyActionV2Contracts.Receipt.class));}
    private ObjectNode validateV2(JsonNode raw){
        LocationInput.keys(raw,"schemaVersion,idempotencyKey,action,reason,copies","");if(LocationInput.integer(raw.get("schemaVersion"),2,2)!=2)invalid();key(LocationInput.text(raw.get("idempotencyKey"),36,true));LocationInput.text(raw.get("reason"),500,true);String action=LocationInput.word(raw.get("action"),ACTIONS.toArray(String[]::new));
        if(!raw.path("copies").isArray()||raw.path("copies").isEmpty()||raw.path("copies").size()>100)invalid();Set<Long> ids=new HashSet<>();
        for(JsonNode c:raw.path("copies")){String extra=action.equals("MOVE")?",targetLocation":action.equals("RESTORE")?",targetLocation,restoreOfActionId,restoreTo":"";LocationInput.keys(c,"copyId,expectedVersion"+extra,"");if(!ids.add(LocationInput.number(c.get("copyId"),true)))invalid();LocationInput.number(c.get("expectedVersion"),false);
            if(action.equals("MOVE")||action.equals("RESTORE")){JsonNode target=c.get("targetLocation");String kind=LocationInput.word(target==null?null:target.get("kind"),"UNASSIGNED","TEXT","REGISTERED");
                if(kind.equals("UNASSIGNED"))LocationInput.keys(target,"kind","");else if(kind.equals("TEXT")){LocationInput.keys(target,"kind,text","");LocationInput.text(target.get("text"),200,true);}else{if(!action.equals("MOVE"))invalid();LocationInput.keys(target,"kind,locationId,expectedAddressVersion","");LocationInput.number(target.get("locationId"),true);JsonNode v=target.get("expectedAddressVersion");LocationInput.keys(v,"location,storey,bookshelf","");for(String name:List.of("location","storey","bookshelf"))LocationInput.number(v.get(name),false);}}
            if(action.equals("RESTORE")){LocationInput.number(c.get("restoreOfActionId"),true);LocationInput.word(c.get("restoreTo"),"DISABLED");}}
        ObjectNode result=raw.deepCopy();result.put("idempotencyKey",key(raw.path("idempotencyKey").asText()));return result;
    }
    private PlanV2 buildV2(JsonNode request){
        PreviewRequest bridge=new PreviewRequest();bridge.setAction(request.path("action").asText());bridge.setReason(request.path("reason").asText());bridge.setIdempotencyKey(request.path("idempotencyKey").asText());List<CopyInput> inputs=new ArrayList<>();Map<Long,String> rawTargets=new TreeMap<>();Map<Long,JsonNode> targets=new HashMap<>();Set<Long> addressIds=new TreeSet<>();
        for(JsonNode input:request.path("copies")){Long id=input.path("copyId").asLong();BookCopy c=readCopy(id);CopyInput old=new CopyInput();old.setCopyId(id);old.setExpectedVersion(input.path("expectedVersion").asLong());if(input.has("restoreOfActionId"))old.setRestoreOfActionId(input.path("restoreOfActionId").asLong());old.setRestoreTo(input.has("restoreTo")?input.path("restoreTo").asText():null);inputs.add(old);JsonNode target=input.get("targetLocation");targets.put(id,target);
            String next=c.getLocation();if(target!=null){if(target.path("kind").asText().equals("TEXT"))next=target.path("text").asText();if(target.path("kind").asText().equals("UNASSIGNED"))next=null;if(target.has("locationId"))addressIds.add(target.path("locationId").asLong());}rawTargets.put(id,next);if(c.getRegisteredLocationId()!=null)addressIds.add(c.getRegisteredLocationId());}
        bridge.setCopies(inputs);Plan old=build(bridge,true,rawTargets);addresses.lockAddresses(addressIds);var current=placements.find(rawTargets.keySet());List<BookCopyActionV2Contracts.CopyChange> changes=new ArrayList<>();List<String> warning=new ArrayList<>(old.warnings());
        for(CopyChange c:old.copies()){CopyPlacement actual=current.get(c.copyId());PlacementSnapshot before=CopyPlacementService.snapshot(actual,c.beforeLocation());Placement next=actual.placement();JsonNode target=targets.get(c.copyId());
            if(target!=null){String kind=target.path("kind").asText();if(kind.equals("REGISTERED")){AddressSnapshot address=addresses.get(target.path("locationId").asLong()).address();if(!address.selectable())throw failure(409,"LOCATION_UNAVAILABLE","目标地址不可选",null,c.copyId(),c.ordinal());JsonNode expected=target.path("expectedAddressVersion");if(expected.path("location").asLong()!=address.locationVersion()||expected.path("storey").asLong()!=address.storeyVersion()||expected.path("bookshelf").asLong()!=address.bookshelfVersion())throw failure(409,"LOCATION_STALE","目标地址版本已变化",null,c.copyId(),c.ordinal());next=new Placement("REGISTERED",null,address);}else next=CopyPlacementService.placement(null,c.afterLocation(),null);}
            PlacementSnapshot after=new PlacementSnapshot(next,c.afterLocation());if(bridge.getAction().equals("MOVE")&&Objects.equals(placementId(before),placementId(after))&&Objects.equals(c.beforeLocation(),c.afterLocation()))throw failure(409,"INVALID_TRANSITION","位置没有变化",null,c.copyId(),c.ordinal());
            ObjectNode node=json.valueToTree(c);node.set("beforePlacement",json.valueToTree(before));node.set("afterPlacement",json.valueToTree(after));if(actual.lastLocationActionId()==null)node.putNull("beforeLocationActionId");else node.put("beforeLocationActionId",actual.lastLocationActionId());node.put("locationSourceBehavior",target==null?"KEEP":"THIS_EVENT");changes.add(read(write(node),BookCopyActionV2Contracts.CopyChange.class));if(actual.locationReferenceAnomaly()&&!warning.contains("LOCATION_REFERENCE_ANOMALY"))warning.add("LOCATION_REFERENCE_ANOMALY");}
        return new PlanV2(old.action(),changes,old.books(),warning,old.metadataDigests());
    }
    private Long placementId(PlacementSnapshot snapshot){return snapshot.placement().registeredLocation()==null?null:snapshot.placement().registeredLocation().locationId();}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Context context(Long id,boolean admin){
        BookCopy copy=readCopy(id);var detail=queries.detail(id);if(detail==null)throw failure(404,"COPY_NOT_FOUND","实体册不存在",null,id,null);
        String state=projection.classification(id);List<ActionPermission> allowed=new ArrayList<>();Book book=books.selectById(copy.getBookId());
        for(String action:ACTIONS){String reason=null;try{role(action,admin);eligible(copy,state,action,copy.getLastStateActionId(),null);
                int dt=action.equals("WRITE_OFF")?-1:action.equals("RESTORE")?1:0,dv=action.equals("DISABLE")?-1:action.equals("ENABLE")?1:action.equals("WRITE_OFF")&&state.equals("AVAILABLE")?-1:0;
                long t=(long)book.getStockTotal()+dt,v=(long)book.getStockAvailable()+dv;if(copy.getActionVersion()==Long.MAX_VALUE||t<0||t>Integer.MAX_VALUE||v<0||v>t)throw failure(409,"STOCK_CONSTRAINT","动作后库存超出范围，请核对",null,id,null);
            }catch(BookCopyActionException e){reason=e.reasonCode();}
            allowed.add(new ActionPermission(action,reason==null,reason,state.equals("WRITTEN_OFF")?copy.getLastStateActionId():null));}
        var v2=projection.forBook(copy.getBookId());var warning=new ArrayList<>(warnings(List.of(v2)));if(detail.copy().locationReferenceAnomaly())warning.add("LOCATION_REFERENCE_ANOMALY");return new Context(detail.copy(),v2,allowed,warning);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public History history(Long id,int page,int size){
        readCopy(id);long p=Math.max(1,page);int s=Math.max(1,Math.min(100,size));
        var sources=jdbc.query("SELECT r.id,r.source_type,r.purchase_order_id,c.purchase_item_id,c.stock_flow_id,r.operator_id,r.confirmed_at FROM inventory_intake_copy c JOIN inventory_intake_request r ON r.id=c.intake_id WHERE c.copy_id=? AND r.state='COMMITTED'",(rs,n)->new IntakeSource(rs.getLong(1),rs.getString(2),rs.getObject(3,Long.class),rs.getObject(4,Long.class),rs.getLong(5),rs.getLong(6),rs.getObject(7,LocalDateTime.class).toInstant(ZoneOffset.UTC).toString()),id);
        return new History(events.history(id,s,(p-1)*s),events.count(id),p,s,sources.isEmpty()?null:sources.get(0));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public JsonNode historyAny(Long id,int page,int size){
        History history=history(id,page,size);ObjectNode result=json.valueToTree(history);if(history.records().isEmpty())return result;
        Map<Long,Map<String,Object>> locationRows=new HashMap<>();List<Long> ids=history.records().stream().map(ActionEvent::eventId).toList();
        jdbc.query("SELECT a.id,r.schema_version,x.* FROM book_copy_action a JOIN book_copy_action_request r ON r.id=a.request_id LEFT JOIN book_copy_action_location x ON x.event_id=a.id WHERE a.id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",rs->{Map<String,Object> row=new HashMap<>();row.put("schema",rs.getInt("schema_version"));row.put("event",rs.getObject("event_id"));row.put("before",rs.getString("before_json"));row.put("after",rs.getString("after_json"));row.put("source",rs.getObject("before_source_action_id",Long.class));row.put("behavior",rs.getString("source_behavior"));locationRows.put(rs.getLong("id"),row);},ids.toArray());
        for(JsonNode item:result.path("records")){ObjectNode node=(ObjectNode)item;var row=locationRows.get(item.path("eventId").asLong());node.put("schemaVersion",(Integer)row.get("schema"));if(row.get("event")==null)node.putNull("locationChange");else{String behavior=(String)row.get("behavior");Long source=(Long)row.get("source"),after=behavior.equals("THIS_EVENT")?item.path("eventId").asLong():source;node.set("locationChange",json.valueToTree(new LocationChange(1,read((String)row.get("before"),PlacementSnapshot.class),read((String)row.get("after"),PlacementSnapshot.class),source,behavior,after)));}}
        return result;
    }
    private Plan build(PreviewRequest request){return build(request,false,null);}
    private Plan build(PreviewRequest request,boolean structured,Map<Long,String> rawTargets){
        Map<Long,Long> located=new TreeMap<>();for(CopyInput input:request.getCopies()){BookCopy c=readCopy(input.getCopyId());located.put(c.getId(),c.getBookId());}
        Map<Long,Book> lockedBooks=new TreeMap<>();for(Long bookId:new TreeSet<>(located.values())){Book b=books.lockById(bookId);if(b==null)throw failure(404,"BOOK_NOT_FOUND","关联书目不存在",null,null,null);lockedBooks.put(bookId,b);}
        Map<Long,BookCopy> locked=new TreeMap<>();for(Long id:located.keySet()){BookCopy c=copies.lockById(id);if(c==null||!located.get(id).equals(c.getBookId()))throw failure(409,"PREVIEW_STALE","册归属已变化",null,id,null);locked.put(id,c);}
        List<CopyChange> changes=new ArrayList<>();Map<Long,int[]> deltas=new TreeMap<>();int ordinal=0;
        for(CopyInput input:request.getCopies()){
            ordinal++;BookCopy c=locked.get(input.getCopyId());if(!structured&&c.getRegisteredLocationId()!=null)throw failure(409,"LOCATION_CONTRACT_UPGRADE_REQUIRED","请刷新并使用新版位置办理",null,c.getId(),ordinal);if(!input.getExpectedVersion().equals(c.getActionVersion()))throw failure(409,"PREVIEW_STALE","册版本已变化，请重新读取",null,c.getId(),ordinal);
            String state=projection.classification(c.getId());eligible(c,state,request.getAction(),input.getRestoreOfActionId(),ordinal);
            String next=c.getStatus(),location=c.getLocation();int dt=0,dv=0;
            switch(request.getAction()){
                case "MOVE"->{location=structured?rawTargets.get(c.getId()):location(input.getTargetLocation());if(!structured&&Objects.equals(location,c.getLocation()))throw failure(409,"INVALID_TRANSITION","位置没有变化",null,c.getId(),ordinal);}
                case "DISABLE"->{next="DISABLED";dv=-1;}
                case "ENABLE"->{next="AVAILABLE";dv=1;}
                case "WRITE_OFF"->{next="WRITTEN_OFF";dt=-1;dv=state.equals("AVAILABLE")?-1:0;}
                case "RESTORE"->{next="DISABLED";dt=1;location=structured?rawTargets.get(c.getId()):location(input.getTargetLocation());}
                default->invalid();
            }
            if(c.getActionVersion()==Long.MAX_VALUE)throw failure(409,"STOCK_CONSTRAINT","册版本超出可保存范围",null,c.getId(),ordinal);
            changes.add(new CopyChange(ordinal,c.getId(),c.getBookId(),c.getBarcode(),c.getStatus(),next,c.getLocation(),location,c.getActionVersion(),c.getActionVersion()+1,c.getLastStateActionId(),input.getRestoreOfActionId(),dt,dv));
            int[] delta=deltas.computeIfAbsent(c.getBookId(),id->new int[5]);delta[0]+=dt;delta[1]+=dv;delta[2]+=(next.equals("AVAILABLE")?1:0)-(state.equals("AVAILABLE")?1:0);delta[3]+=(next.equals("DISABLED")?1:0)-(state.equals("DISABLED")?1:0);delta[4]+=(next.equals("WRITTEN_OFF")?1:0)-(state.equals("WRITTEN_OFF")?1:0);
        }
        List<BookChange> bookChanges=new ArrayList<>();Map<Long,String> metadata=new TreeMap<>();List<CopyInventoryProjection.V2> before=new ArrayList<>();
        for(var entry:deltas.entrySet()){
            Book b=lockedBooks.get(entry.getKey());int[] d=entry.getValue();long total=(long)b.getStockTotal()+d[0],available=(long)b.getStockAvailable()+d[1];
            if(total<0||total>Integer.MAX_VALUE||available<0||available>total)throw failure(409,"STOCK_CONSTRAINT","动作后库存超出范围，请核对",null,null,null);
            var v2=projection.forBook(b.getId());before.add(v2);metadata.put(b.getId(),catalog.digest(catalog.metadata(b)));
            bookChanges.add(new BookChange(b.getId(),b.getIsbn(),b.getTitle(),b.getStockTotal(),b.getStockAvailable(),(int)total,(int)available,d[0],d[1],v2,CopyInventoryProjection.after(v2,total,available,d[2],d[3],d[4])));
        }
        return new Plan(request.getAction(),changes,bookChanges,warnings(before),metadata);
    }
    private void eligible(BookCopy c,String state,String action,Long restore,Integer ordinal){
        List<Long> loanBooks=jdbc.queryForList("SELECT book_id FROM borrow_record WHERE copy_id=? AND deleted=0 AND status IN ('BORROWING','OVERDUE')",Long.class,c.getId());
        boolean borrowed=state.equals("BORROWED");if((borrowed&&loanBooks.size()!=1)||(!borrowed&&!loanBooks.isEmpty())||loanBooks.stream().anyMatch(b->!b.equals(c.getBookId())))throw failure(409,"COPY_LINK_ANOMALY","册与当前借阅关联不一致，请核对",null,c.getId(),ordinal);
        if(borrowed)throw failure(409,"COPY_ON_LOAN","已借出实体册请先归还",null,c.getId(),ordinal);
        if(state.equals("UNKNOWN"))throw failure(409,"UNKNOWN_LEGACY_STATUS","原状态或来源待核实",null,c.getId(),ordinal);
        boolean allowed=switch(action){case "MOVE","WRITE_OFF"->state.equals("AVAILABLE")||state.equals("DISABLED");case "DISABLE"->state.equals("AVAILABLE");case "ENABLE"->state.equals("DISABLED");case "RESTORE"->state.equals("WRITTEN_OFF");default->false;};
        if(!allowed)throw failure(409,"INVALID_TRANSITION","当前册状态不允许此动作",null,c.getId(),ordinal);
        if(action.equals("RESTORE")){
            if(restore==null||!restore.equals(c.getLastStateActionId()))throw failure(409,"RESTORE_SOURCE_REQUIRED","请引用本册当前报损事件",null,c.getId(),ordinal);
            ActionEvent source=events.read(restore);if(source==null||!source.copyId().equals(c.getId())||!source.action().equals("WRITE_OFF"))throw failure(409,"RESTORE_SOURCE_REQUIRED","报损来源不匹配",null,c.getId(),ordinal);
            if(source.restoredByActionId()!=null)throw failure(409,"ALREADY_RESTORED","该次报损已经恢复",null,c.getId(),ordinal);
        }
    }
    private void validate(PreviewRequest r){
        if(r==null||r.getAction()==null||!ACTIONS.contains(r.getAction())||r.getCopies()==null||r.getCopies().isEmpty()||r.getCopies().size()>100)invalid();r.setIdempotencyKey(key(r.getIdempotencyKey()));text(r.getReason(),500,true);
        Set<Long> ids=new HashSet<>();for(CopyInput c:r.getCopies()){
            if(c==null||c.getCopyId()==null||c.getCopyId()<=0||!ids.add(c.getCopyId())||c.getExpectedVersion()==null||c.getExpectedVersion()<0)invalid();
            if(r.getAction().equals("MOVE")||r.getAction().equals("RESTORE")){location(c.getTargetLocation());}else if(c.getTargetLocation()!=null)invalid();
            if(r.getAction().equals("RESTORE")){if(c.getRestoreOfActionId()==null||c.getRestoreOfActionId()<=0||!"DISABLED".equals(c.getRestoreTo()))invalid();}else if(c.getRestoreOfActionId()!=null||c.getRestoreTo()!=null)invalid();
        }
    }
    private String location(com.library.dto.BookCopyActionContracts.LocationInput l){if(l==null)invalid();if("UNASSIGNED".equals(l.getKind())){if(l.getText()!=null)invalid();return null;}if(!"TEXT".equals(l.getKind()))invalid();text(l.getText(),200,true);return l.getText();}
    private void text(String value,int max,boolean required){try{IntakeCatalog.text(value,max,required);}catch(InventoryIntakeException e){invalid();}}
    private String key(String value){if(value==null||!value.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))invalid();return value.toLowerCase(Locale.ROOT);}
    private void role(String action,boolean admin){if(!admin&&(action.equals("WRITE_OFF")||action.equals("RESTORE")))throw failure(403,"ACTION_ROLE_REQUIRED","报损和恢复仅管理员可办理",null,null,null);}
    private BookCopy readCopy(Long id){if(id==null||id<=0)invalid();BookCopy c=copies.selectById(id);if(c==null)throw failure(404,"COPY_NOT_FOUND","实体册不存在",null,id,null);return c;}
    private List<String> warnings(List<CopyInventoryProjection.V2> rows){List<String> result=new ArrayList<>();if(rows.stream().anyMatch(CopyInventoryProjection.V2::hasHistoricalDifference))result.add("HISTORICAL_STOCK_DIFFERENCE");if(rows.stream().anyMatch(CopyInventoryProjection.V2::hasUnknownStatus))result.add("UNKNOWN_LEGACY_STATUS");if(rows.stream().anyMatch(CopyInventoryProjection.V2::hasLoanLinkAnomaly))result.add("COPY_LINK_ANOMALY");return result;}
    private Row byIdRow(Long id,boolean lock){var rows=jdbc.query("SELECT * FROM book_copy_action_request WHERE id=?"+(lock?" FOR UPDATE":""),this::row,id);return rows.isEmpty()?null:rows.get(0);}
    private Row byKeyRow(String key,boolean lock){var rows=jdbc.query("SELECT * FROM book_copy_action_request WHERE idempotency_key=?"+(lock?" FOR UPDATE":""),this::row,bytes(key));return rows.isEmpty()?null:rows.get(0);}
    private Row row(ResultSet r,int n)throws SQLException{return new Row(r.getLong("id"),r.getLong("operator_id"),r.getString("action_type"),r.getString("request_hash"),r.getString("preview_digest"),r.getString("state"),r.getInt("schema_version"),r.getObject("expires_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),r.getString("request_json"),r.getString("preview_json"),r.getString("receipt_json"));}
    private void owner(Row row,Long actor){if(row==null)throw failure(404,"REQUEST_NOT_FOUND","动作办理不存在",null,null,null);if(!row.owner().equals(actor))throw failure(403,"ACTION_OWNER_REQUIRED","仅原操作者可查看或确认本次办理",row.id(),null,null);}
    private View view(Row row){if(row.schema()!=1)throw failure(409,"LOCATION_CONTRACT_UPGRADE_REQUIRED","请使用对应版本办理",row.id(),null,null);Plan p=read(row.plan(),Plan.class);return new View(row.id(),row.state(),1,row.action(),row.digest(),row.expires().toString(),p.copies(),p.books(),p.warnings(),row.receipt()==null?null:read(row.receipt(),Receipt.class));}
    private byte[] bytes(String value){return IntakeCatalog.bytes(value);}private LocalDateTime local(Instant value){return LocalDateTime.ofInstant(value,ZoneOffset.UTC);}
    private Long insert(String sql,Object...args){GeneratedKeyHolder key=new GeneratedKeyHolder();jdbc.update(connection->{var p=connection.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},key);return Objects.requireNonNull(key.getKey()).longValue();}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw failure(500,"ACTION_SAVE_FAILED","动作记录处理失败，请查询原办理",null,null,null);}}
    private <T>T read(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception e){throw failure(500,"ACTION_SAVE_FAILED","动作记录读取失败，请查询原办理",null,null,null);}}
    private static void invalid(){throw failure(400,"INVALID_INPUT","逐册动作参数不符合要求",null,null,null);}
    private static BookCopyActionException failure(int code,String reason,String message,Long request,Long copy,Integer ordinal){return new BookCopyActionException(code,reason,message,request,copy,ordinal);}
}
