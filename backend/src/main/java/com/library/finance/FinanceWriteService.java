package com.library.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.library.finance.FinanceSupport.*;
import static com.library.finance.FinanceCommands.canonicalHash;

/** One writer for the permanent operation registry and the append-only receipt ledger. */
@Service
public class FinanceWriteService {
    final FinanceReadService read;final JdbcTemplate jdbc;final FinanceReceiptPolicy policy;
    final TransactionTemplate transaction;
    static final Set<String> OPEN=Set.of("RESERVED","DRAFT_READY","PREVIEWED");
    public FinanceWriteService(FinanceReadService read,FinanceReceiptPolicy policy,PlatformTransactionManager manager) {
        this.read=read;this.jdbc=read.jdbcTemplate();this.policy=policy;transaction=new TransactionTemplate(manager);transaction.setIsolationLevelName("ISOLATION_READ_COMMITTED");
    }
    JdbcTemplate jdbcTemplate(){return jdbc;}
    FinanceReadService readService(){return read;}
    TransactionTemplate transactions(){return transaction;}
    void authorize(Authentication a){read.admin(a);}
    record Result(JsonNode data,Failure failure) {}
    record User(long id,String role,int status,int deleted,long revision,long token,String name,String card) {}
    User user(ResultSet r,int ignored)throws SQLException {return new User(r.getLong("id"),r.getString("role"),r.getInt("status"),r.getInt("deleted"),r.getLong("member_revision"),r.getLong("token_version"),r.getString("real_name"),null);}
    static final String USER="SELECT id,role,status,deleted,member_revision,token_version,real_name FROM sys_user";
    User lockUsers(long actor,long member,Authentication a) {
        var users=jdbc.query(USER+" WHERE id IN (?,?) ORDER BY id FOR UPDATE",this::user,Math.min(actor,member),Math.max(actor,member));
        User current=users.stream().filter(u->u.id==actor).findFirst().orElse(null);
        if(current==null||!current.role.equals("ADMIN")||current.status!=1||current.deleted!=0||a.getAuthorities().stream().noneMatch(x->x.getAuthority().equals("ROLE_ADMIN")))throw failure(403,"ADMIN_REQUIRED");
        if(!(a.getDetails() instanceof TokenVersion version)||version.value()!=current.token)throw failure(401,"UNAUTHENTICATED");
        live(a,read.now());
        return users.stream().filter(u->u.id==member).findFirst().orElse(null);
    }
    void live(Authentication a,LocalDateTime now) {if(!(a.getDetails() instanceof TokenVersion v)||v.expiresAt()==null||!now.toInstant(ZoneOffset.UTC).isBefore(v.expiresAt()))throw failure(401,"UNAUTHENTICATED");}
    record Op(String id,long actor,long member,String key,String reserve,String editor,long epoch,String claim,String claimHash,Long claimEpoch,String state,String draft,String input,String request,String preview,String cancelKey,String cancelHash,String cancelInput,String cancelResult,String result,LocalDateTime created,LocalDateTime expires,LocalDateTime updated) {}
    Op operation(ResultSet r,int ignored)throws SQLException {return new Op(text(r.getBytes("id")),r.getLong("actor_id"),r.getLong("user_id"),text(r.getBytes("operation_key")),r.getString("reserve_hash"),text(r.getBytes("editor_id")),r.getLong("editor_epoch"),text(r.getBytes("last_claim_key")),r.getString("last_claim_hash"),(Long)r.getObject("last_claim_from_epoch"),r.getString("state"),r.getString("draft_hash"),r.getString("draft_input_json"),text(r.getBytes("request_id")),r.getString("preview_digest"),text(r.getBytes("cancel_key")),r.getString("cancel_hash"),r.getString("cancel_input_json"),r.getString("cancel_result_json"),r.getString("result_json"),r.getObject("created_at",LocalDateTime.class),r.getObject("draft_expires_at",LocalDateTime.class),r.getObject("updated_at",LocalDateTime.class));}
    Op op(long actor,String key,boolean lock) {var rows=jdbc.query("SELECT * FROM member_finance_operation WHERE actor_id=? AND operation_key=?"+(lock?" FOR UPDATE":""),this::operation,actor,bytes(key));return rows.isEmpty()?null:rows.get(0);}
    record Request(String id,long account,String operation,long actor,String key,String action,String hash,String policy,String digest,String input,String dependencies,String preview,String state,String result,LocalDateTime created,LocalDateTime expires) {}
    Request request(ResultSet r,int ignored)throws SQLException {return new Request(text(r.getBytes("id")),r.getLong("account_id"),text(r.getBytes("operation_id")),r.getLong("actor_id"),text(r.getBytes("operation_key")),r.getString("action"),r.getString("request_hash"),r.getString("policy_digest"),r.getString("preview_digest"),r.getString("input_json"),r.getString("dependencies_json"),r.getString("preview_json"),r.getString("state"),r.getString("result_json"),r.getObject("created_at",LocalDateTime.class),r.getObject("expires_at",LocalDateTime.class));}
    Request req(String id,boolean lock) {var rows=jdbc.query("SELECT * FROM member_finance_request WHERE id=?"+(lock?" FOR UPDATE":""),this::request,bytes(id));if(rows.size()!=1)throw failure(500,"INTERNAL_ERROR");return rows.get(0);}
    JsonNode requestView(Request r,long member) {return obj("requestId",r.id,"operationId",r.operation,"operationKey",r.key,"memberId",Long.toString(member),"accountId",Long.toString(r.account),"fund",read(r.input).get("fund"),"currency","CNY","action",r.action,"state",r.state,"input",read(r.input),"inputHash",r.hash,"previewDigest",r.digest,"preview",read(r.preview),"createdAt",utc(r.created),"expiresAt",utc(r.expires),"result",r.result==null?null:read(r.result));}
    JsonNode view(Op o) {
        Request r=o.request==null?null:req(o.request,false);
        return obj("operationId",o.id,"operationKey",o.key,"memberId",Long.toString(o.member),"actorId",Long.toString(o.actor),"editor",obj("editorId",o.editor,"epoch",Long.toString(o.epoch)),"state",o.state,"draftHash",o.draft,"input",r!=null?read(r.input):o.input==null?null:read(o.input),"request",r==null?null:requestView(r,o.member),"cancel",o.cancelResult==null?null:read(o.cancelResult),"result",o.result==null?null:read(o.result),"createdAt",utc(o.created),"draftExpiresAt",utc(o.expires),"updatedAt",utc(o.updated),"serverNow",utc(read.now()));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public JsonNode observe(String key,String requestId,String query,Authentication a) {
        authorize(a);if(query!=null)throw failure(400,"UNKNOWN_FIELD");long actor=Long.parseLong(a.getName());
        if(requestId!=null) {
            FinanceCommands.uuid(JSON.valueToTree(requestId));var rows=jdbc.query("SELECT * FROM member_finance_request WHERE id=? AND actor_id=?",this::request,bytes(requestId),actor);
            if(rows.isEmpty())throw failure(404,"REQUEST_NOT_FOUND");var r=rows.get(0);return requestView(r,op(actor,r.key,false).member);
        }
        Op o;if(key!=null) {FinanceCommands.key(JSON.valueToTree(key));o=op(actor,key,false);if(o==null)throw failure(404,"OPERATION_NOT_FOUND");}
        else {var rows=jdbc.query("SELECT * FROM member_finance_operation WHERE actor_id=? AND state IN ('RESERVED','DRAFT_READY','PREVIEWED')",this::operation,actor);o=rows.isEmpty()?null:rows.get(0);}
        return o==null?JSON.nullNode():view(o);
    }
    public Result command(String route,String kind,ObjectNode body,Authentication a) {
        authorize(a);String key=body.path("operationKey").asText();if(route!=null&&!route.equals(key))throw failure(400,"INVALID_INPUT");
        return transaction.execute(status->execute(key,kind,body,a));
    }
    Result execute(String key,String kind,ObjectNode b,Authentication a) {
        long actor=Long.parseLong(a.getName());Op located=op(actor,key,false);
        long member=b.has("memberId")?Long.parseLong(b.get("memberId").asText()):b.has("input")?Long.parseLong(b.path("input").path("memberId").asText()):located==null?actor:located.member;
        // Determine stable target without locking business rows, then take both user locks in order.
        User target=lockUsers(actor,member,a);Op o=op(actor,key,true);LocalDateTime now=read.now();
        if(o!=null&&o.member!=member)throw failure(409,kind.equals("reserve")?"OPERATION_KEY_REUSED":kind.equals("cancel")&&o.cancelKey!=null?(o.cancelKey.equals(b.path("cancelKey").asText())?"CANCEL_KEY_REUSED":"CANCEL_INTENT_FROZEN"):"INVALID_INPUT");
        if(o!=null)lockAccountRequest(o);
        now=read.now();live(a,now); // Includes reserve/cancel replay after every business-row lock wait.
        if(kind.equals("reserve"))return reserve(actor,member,o,b,target,now,a);
        if(kind.equals("cancel"))return cancel(actor,member,o,b,target,now);
        if(o==null)throw failure(404,"OPERATION_NOT_FOUND");
        if(kind.equals("resolve")) {expire(o,now);return success(op(actor,key,false));}
        if(kind.equals("claim"))return claim(o,b,now);
        fence(o,b.get("editor"));
        if(kind.equals("confirm")&&o.state.equals("COMMITTED")) {digest(o,b);return success(o);}
        if(kind.equals("confirm")&&o.request!=null)digest(o,b);
        if(!OPEN.contains(o.state))return refused(o,o.state,409);
        if(expire(o,now))return refused(op(actor,key,false),"EXPIRED",409);
        return switch(kind) {
            case "draft"->draft(o,b,target,now);
            case "preview"->preview(o,b,target,now);
            case "confirm"->confirm(o,b,target,now);
            default->throw failure(400,"INVALID_INPUT");};
    }
    Result success(Op o){return new Result(view(o),null);}
    Result refused(Op o,String code,int status){return new Result(null,failure(status,code).withOperation(view(o)));}
    void fence(Op o,JsonNode editor) {if(!o.editor.equals(editor.path("editorId").asText())||!Long.toString(o.epoch).equals(editor.path("epoch").asText()))throw failure(409,"EDITOR_CHANGED");}
    void digest(Op o,JsonNode b) {if(o.preview==null||!o.preview.equals(b.path("previewDigest").asText()))throw failure(409,"PREVIEW_DIGEST_MISMATCH");}
    void changed(int n){if(n!=1)throw failure(500,"INTERNAL_ERROR");}
    long insert(String sql,Object... args) {
        GeneratedKeyHolder key=new GeneratedKeyHolder();int n=jdbc.update(c->{var p=c.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},key);changed(n);
        if(key.getKey()==null)throw failure(500,"INTERNAL_ERROR");return key.getKey().longValue();
    }
    void target(User u) {if(u==null||!u.role.equals("READER")||u.deleted!=0)throw failure(404,"MEMBER_NOT_FOUND");}
    Result reserve(long actor,long member,Op o,ObjectNode b,User target,LocalDateTime now,Authentication authentication) {
        String hash=canonicalHash(b);
        if(o!=null) {if(o.reserve==null)return refused(o,"CANCELLED",409);if(!o.reserve.equals(hash))throw failure(409,"OPERATION_KEY_REUSED");expire(o,now);return success(op(actor,o.key,false));}
        target(target);
        var active=jdbc.query("SELECT * FROM member_finance_operation WHERE actor_id=? AND state IN ('RESERVED','DRAFT_READY','PREVIEWED') FOR UPDATE",this::operation,actor);
        now=read.now();live(authentication,now);
        if(target.status!=1)throw failure(422,"RULE_NOT_CONFIRMED").field("identity.accountStatus","D06_UNCONFIRMED");
        if(!policy.snapshot(now).enabled())throw failure(422,"RULE_NOT_CONFIRMED").field("policy","ACTIVATION_UNCONFIRMED");
        // Expire only the actor's prior item; its user lock is not needed to destroy draft metadata.
        for(Op old:active)if(old.request!=null||!expire(old,now))return refused(old,"ACTIVE_OPERATION_EXISTS",409);
        String id=UUID.randomUUID().toString(),key=b.get("operationKey").asText();
        changed(jdbc.update("INSERT INTO member_finance_operation(id,actor_id,user_id,operation_key,reserve_hash,editor_id,editor_epoch,state,created_at,draft_expires_at,updated_at) VALUES (?,?,?,?,?,?,1,'RESERVED',?,?,?)",bytes(id),actor,member,bytes(key),hash,bytes(b.get("editorId").asText()),now,now.plusDays(1),now));return success(op(actor,key,false));
    }
    Result claim(Op o,ObjectNode b,LocalDateTime now) {
        String key=b.path("claimKey").asText(),hash=canonicalHash(b);long expected=Long.parseLong(b.path("expectedEpoch").asText());
        if(key.equals(o.claim)) {
            if(!hash.equals(o.claimHash))throw failure(409,"CLAIM_KEY_REUSED");
            if(expected<Long.MAX_VALUE&&o.epoch==expected+1&&o.editor.equals(b.path("editorId").asText())) {if(expire(o,now))return refused(op(o.actor,o.key,false),"EXPIRED",409);return success(o);}
            throw failure(409,"EDITOR_CHANGED");
        }
        if(!OPEN.contains(o.state))return success(o);
        if(expire(o,now))return success(op(o.actor,o.key,false));
        if(o.epoch!=expected||expected==Long.MAX_VALUE)throw failure(409,"EDITOR_CHANGED");
        changed(jdbc.update("UPDATE member_finance_operation SET editor_id=?,editor_epoch=?,last_claim_key=?,last_claim_hash=?,last_claim_from_epoch=?,updated_at=? WHERE id=? AND editor_epoch=?",bytes(b.path("editorId").asText()),expected+1,bytes(key),hash,expected,now,bytes(o.id),expected));return success(op(o.actor,o.key,false));
    }
    Result draft(Op o,ObjectNode b,User target,LocalDateTime now) {
        JsonNode input=b.get("input");String hash=canonicalHash(obj("schemaVersion",1,"actorId",Long.toString(o.actor),"memberId",Long.toString(o.member),"operationKey",o.key,"canonicalInput",input));
        if(o.draft!=null) {if(!o.draft.equals(hash))throw failure(409,"DRAFT_ALREADY_FROZEN");return success(o);}
        target(target);var snapshot=policy.snapshot(now);var blocks=blocks(target,input,read.account(o.member,input.get("fund").asText()),snapshot,now,false);
        if(!blocks.isEmpty())throw failure(blocks.get(0).path("code").asText().equals("FUTURE_PAYMENT")||blocks.get(0).path("code").asText().equals("SAME_DAY_REQUIRED")||blocks.get(0).path("code").asText().equals("BEFORE_CUTOFF")?400:422,top(blocks.get(0))).field(blocks.get(0).path("path").asText(),blocks.get(0).path("code").asText());
        changed(jdbc.update("UPDATE member_finance_operation SET state='DRAFT_READY',draft_hash=?,draft_input_json=?,updated_at=? WHERE id=? AND state='RESERVED'",hash,input.toString(),now,bytes(o.id)));return success(op(o.actor,o.key,false));
    }
    boolean expire(Op o,LocalDateTime now) {
        if(!OPEN.contains(o.state))return false;LocalDateTime expires=o.request==null?o.expires:req(o.request,false).expires;
        if(now.isBefore(expires))return false;terminal(o,"EXPIRED","EXPIRED",null,now);return true;
    }
    void terminal(Op o,String state,String reason,JsonNode receipt,LocalDateTime now) {
        String result=obj("state",state,"resolvedAt",utc(now),"reasonCode",reason,"receipt",receipt).toString();
        if(o.request!=null)changed(jdbc.update("UPDATE member_finance_request SET state=?,result_json=?,resolved_at=? WHERE id=? AND state='PREVIEWED'",state,result,now,bytes(o.request)));
        changed(jdbc.update("UPDATE member_finance_operation SET state=?,draft_input_json=NULL,result_json=?,resolved_at=?,updated_at=? WHERE id=? AND state IN ('RESERVED','DRAFT_READY','PREVIEWED')",state,result,now,now,bytes(o.id)));
    }
    Result cancel(long actor,long member,Op o,ObjectNode b,User target,LocalDateTime now) {
        String key=b.path("cancelKey").asText(),hash=canonicalHash(b);
        if(o!=null&&o.cancelKey!=null) {if(!o.cancelKey.equals(key))throw failure(409,"CANCEL_INTENT_FROZEN");if(!o.cancelHash.equals(hash))throw failure(409,"CANCEL_KEY_REUSED");return new Result(read(o.cancelResult),null);}
        if(o==null) {
            if(!b.path("editor").path("epoch").asText().equals("0")||!b.get("previewDigest").isNull())throw failure(400,"INVALID_INPUT");
            // The FK target must exist, but cancellation is independent of new-reader eligibility.
            if(target==null)throw failure(404,"MEMBER_NOT_FOUND");
            String id=UUID.randomUUID().toString(),opKey=b.path("operationKey").asText();
            JsonNode result=obj("state","CANCELLED","resolvedAt",utc(now),"reasonCode","CANCELLED","receipt",null);
            JsonNode receipt=cancelReceipt(b,hash,"CANCELLED","CANCELLED",now,null,null);
            changed(jdbc.update("INSERT INTO member_finance_operation(id,actor_id,user_id,operation_key,editor_id,editor_epoch,state,cancel_key,cancel_hash,cancel_input_json,cancel_result_json,result_json,created_at,draft_expires_at,updated_at,resolved_at) VALUES (?,?,?,?,?,1,'CANCELLED',?,?,?,?,?,?,?,?,?)",bytes(id),actor,member,bytes(opKey),bytes(b.path("editor").path("editorId").asText()),bytes(key),hash,b.toString(),receipt.toString(),result.toString(),now,now.plusDays(1),now,now));return new Result(receipt,null);
        }
        if(b.path("editor").path("epoch").asText().equals("0"))throw failure(409,"OPERATION_APPEARED");
        fence(o,b.get("editor"));
        if(o.preview==null&&!b.get("previewDigest").isNull())throw failure(409,"PREVIEW_DIGEST_MISMATCH");
        if(o.preview!=null) {if(b.get("previewDigest").isNull())throw failure(409,"PREVIEW_BINDING_REQUIRED");digest(o,b);}
        boolean cancelled=OPEN.contains(o.state);
        if(cancelled) {if(expire(o,now)){cancelled=false;}else terminal(o,"CANCELLED","CANCELLED",null,now);o=op(actor,o.key,false);}
        String outcome=cancelled?"CANCELLED":o.state.equals("COMMITTED")?"ALREADY_COMMITTED":"ALREADY_TERMINAL";
        JsonNode receipt=cancelReceipt(b,hash,outcome,o.state,now,o.request,o.result==null?null:read(o.result).get("receipt"));
        changed(jdbc.update("UPDATE member_finance_operation SET cancel_key=?,cancel_hash=?,cancel_input_json=?,cancel_result_json=? WHERE id=? AND cancel_key IS NULL",bytes(key),hash,b.toString(),receipt.toString(),bytes(o.id)));return new Result(receipt,null);
    }
    JsonNode cancelReceipt(JsonNode b,String hash,String outcome,String state,LocalDateTime now,String request,JsonNode receipt) {
        return obj("operationKey",b.get("operationKey"),"memberId",b.get("memberId"),"cancelKey",b.get("cancelKey"),"cancelHash",hash,"previewDigest",b.get("previewDigest"),"reason",b.get("reason"),"outcome",outcome,"state",state,"resolvedAt",utc(now),"requestId",request,"receipt",receipt);
    }
    void lockAccountRequest(Op o) {
        if(o.request==null)return;Request r=req(o.request,false);
        jdbc.queryForObject("SELECT id FROM member_finance_account WHERE id=? FOR UPDATE",Long.class,r.account);req(o.request,true);
    }
    FinanceReadService.Account lockAccount(long member,String fund) {
        var account=read.account(member,fund);
        if(account==null)insert("INSERT INTO member_finance_account(user_id,fund,currency,revision) VALUES (?,?,'CNY',0)",member,fund);
        jdbc.queryForObject("SELECT id FROM member_finance_account WHERE user_id=? AND fund=? AND currency='CNY' FOR UPDATE",Long.class,member,fund);return read.account(member,fund);
    }
    static JsonNode block(String code,String path) {return obj("code",code,"path",path,"message","办理条件未满足，请核对后重新办理");}
    List<JsonNode> blocks(User user,JsonNode input,FinanceReadService.Account account,FinanceReceiptPolicy.Snapshot s,LocalDateTime now,boolean checkVersion) {
        List<JsonNode> blocks=new ArrayList<>();
        if(!s.enabled())blocks.add(block("ACTIVATION_UNCONFIRMED","policy"));
        if(user.status!=1)blocks.add(block("D06_UNCONFIRMED","identity.accountStatus"));
        if(!Set.of("CASH","WECHAT").contains(input.path("payload").path("methodCode").asText()))blocks.add(block("METHOD_DISABLED","payload.methodCode"));
        LocalDateTime actual=FinanceCommands.time(input.path("payload").get("actualAt"));
        if(s.cutoff()!=null&&actual.isBefore(s.cutoff()))blocks.add(block("BEFORE_CUTOFF","payload.actualAt"));
        if(actual.isAfter(now))blocks.add(block("FUTURE_PAYMENT","payload.actualAt"));
        if(!businessDay(actual).equals(businessDay(now)))blocks.add(block("SAME_DAY_REQUIRED","payload.actualAt"));
        if(account!=null&&account.cutoff()!=null&&!account.cutoff().equals(s.cutoff()))blocks.add(block("CUTOFF_MISMATCH","policy.cutoffAt"));
        if(checkVersion&&(user.revision!=Long.parseLong(input.path("memberRevision").asText())||(account==null?0:account.revision())!=Long.parseLong(input.path("financeRevision").asText())))blocks.add(block("VERSION_CHANGED","input"));
        if(account!=null&&account.revision()==Long.MAX_VALUE)blocks.add(block("STORAGE_RANGE","financeRevision"));
        return blocks;
    }
    static String businessDay(LocalDateTime utc) {return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(FinanceReceiptPolicy.ZONE).toLocalDate().toString();}
    static String top(JsonNode block) {return switch(block.path("code").asText()) {case "FUTURE_PAYMENT","SAME_DAY_REQUIRED","BEFORE_CUTOFF"->"INVALID_INPUT";case "VERSION_CHANGED","CUTOFF_MISMATCH"->"DEPENDENCIES_CHANGED";case "STORAGE_RANGE"->"STORAGE_RANGE";case "BUSINESS_FACT_EXISTS"->"BUSINESS_FACT_EXISTS";default->"RULE_NOT_CONFIRMED";};}
    JsonNode identity(User user) {
        var cards=jdbc.query("SELECT card_no FROM member_profile WHERE user_id=?",(r,i)->r.getString(1),user.id);
        return obj("memberId",Long.toString(user.id),"realName",user.name,"cardNo",cards.isEmpty()?null:cards.get(0),"accountStatus",user.status,"memberRevision",Long.toString(user.revision),"role","READER","deleted",false);
    }
    JsonNode ledger(long account) {
        return obj("entries",jdbc.query("SELECT "+FinanceReadService.ENTRY_COLUMNS+" FROM member_finance_entry e WHERE e.account_id=? ORDER BY e.id",read::entry,account).stream().map(FinanceReadService.Entry::value).toList(),"allocations",jdbc.query("SELECT refund_entry_id,lot_entry_id,amount FROM member_finance_allocation WHERE account_id=? ORDER BY refund_entry_id,lot_entry_id",(r,i)->obj("refund",Long.toString(r.getLong(1)),"lot",Long.toString(r.getLong(2)),"amount",money(r.getBigDecimal(3))),account));
    }
    static JsonNode stable(JsonNode node) {
        if(node.isObject()) {ObjectNode value=JSON.createObjectNode();node.fields().forEachRemaining(e->{if(!Set.of("serverNow","asOf","asRecordedAt","calculatedAt").contains(e.getKey()))value.set(e.getKey(),stable(e.getValue()));});return value;}
        if(node.isArray()) {var value=JSON.createArrayNode();node.forEach(n->value.add(stable(n)));return value;}return node;
    }
    JsonNode dependencies(User u,FinanceReadService.Account account,JsonNode input,FinanceReceiptPolicy.Snapshot s,JsonNode before,LocalDateTime now) {
        JsonNode eligibility=obj("version","B40B_MEMBER_ELIGIBILITY_V1","activeReader","ALLOWED","disabledReader","UNCONFIRMED","disabledReaderRuleRef",null,
                "target",obj("memberId",Long.toString(u.id),"accountStatus",u.status,"role",u.role,"deleted",u.deleted!=0,"memberRevision",Long.toString(u.revision)));
        return obj("policy",s.policy(),"activation",s.activation(),"memberEligibility",eligibility,"businessDay",businessDay(now),"identity",identity(u),"account",obj("id",Long.toString(account.id()),"revision",Long.toString(account.revision()),"scopeCutoffAt",utc(account.cutoff()),"openingEntryId",account.opening()==null?null:account.opening().toString()),"balance",stable(before),"ledger",ledger(account.id()),"inputHash",canonicalHash(input));
    }
    JsonNode impact(JsonNode before,JsonNode input,FinanceReceiptPolicy.Snapshot s,LocalDateTime now) {
        BigDecimal amount=parseMoney(input.path("payload").get("amount"));ObjectNode after=before.deepCopy();after.remove(List.of("accountId","openingEntryId","asRecordedAt"));
        long revision=Long.parseLong(before.path("financeRevision").asText());after.put("financeRevision",Long.toString(revision==Long.MAX_VALUE?revision:revision+1));
        if(after.path("scopeCutoffAt").isNull())after.set("scopeCutoffAt",s.policy().get("cutoffAt"));after.put("calculatedAt",utc(now));
        for(String field:List.of("newReceipts","newNet"))after.put(field,money(new BigDecimal(before.path(field).asText()).add(amount)));
        for(String field:List.of("verifiedPaid","balance","feeHeadroom"))if(before.path(field).path("state").asText().equals("KNOWN"))after.set(field,known(new BigDecimal(before.path(field).path("amount").asText()).add(amount)));
        // Output sums use the original 65-digit Money/SignedMoney schema, not the 15-digit per-entry container.
        for(String field:List.of("newReceipts","newRefunds","newNet"))outputRange(after.get(field).asText());
        for(String field:List.of("verifiedPaid","verifiedRefunded","balance","feeHeadroom"))if(after.path(field).path("state").asText().equals("KNOWN"))outputRange(after.path(field).path("amount").asText());
        JsonNode p=input.get("payload");JsonNode entry=obj("ordinal",1,"kind","RECEIPT","amount",p.get("amount"),"openingReceived",null,"openingRefunded",null,"cutoffAt",null,"actualAt",p.get("actualAt"),"methodCode",p.get("methodCode"),"reversesEntryId",null,"replacesEntryId",null,"allocations",List.of());
        return obj("before",before,"after",after,"entries",List.of(entry),"lotChanges",List.of(),"cashExecution","NONE","entitlementEffect","NONE");
    }
    void outputRange(String value){String integer=value.substring(0,value.indexOf('.')).replace("-","");if(integer.length()>65)throw failure(422,"STORAGE_RANGE");}
    boolean factExists(JsonNode input) {return jdbc.queryForObject("SELECT COUNT(*) FROM member_finance_entry WHERE root_fact_key=?",Long.class,bytes(input.path("payload").path("factKey").asText()))!=0;}
    String factMessage(JsonNode input) {
        var rows=jdbc.query("SELECT e.id,e.request_id,a.user_id,a.fund FROM member_finance_entry e JOIN member_finance_account a ON a.id=e.account_id WHERE e.root_fact_key=?",(r,i)->"此事实已登记：条目 "+r.getLong(1)+"，请求 "+text(r.getBytes(2))+"，会员 "+r.getLong(3)+"，资金 "+r.getString(4)+"；请到账本核查",bytes(input.path("payload").path("factKey").asText()));
        return rows.size()==1?rows.get(0):"此事实已登记，请到账本核查";
    }
    Result preview(Op o,ObjectNode b,User target,LocalDateTime now) {
        if(o.draft==null)throw failure(409,"DRAFT_REQUIRED");if(!o.draft.equals(b.path("draftHash").asText()))throw failure(409,"DRAFT_HASH_MISMATCH");
        if(o.request!=null)return success(o);
        if(target==null||target.deleted!=0||!target.role.equals("READER")) {terminal(o,"REJECTED","MEMBER_NOT_FOUND",null,now);return refused(op(o.actor,o.key,false),"DEPENDENCIES_CHANGED",409);}
        JsonNode input=read(o.input);var account=lockAccount(o.member,input.path("fund").asText());var s=policy.snapshot(now);
        List<JsonNode> blocks=blocks(target,input,account,s,now,true);if(factExists(input))blocks.add(obj("code","BUSINESS_FACT_EXISTS","path","payload.factKey","message",factMessage(input)));
        JsonNode before=read.balance(o.member,input.path("fund").asText(),now),impact=impact(before,input,s,now),deps=dependencies(target,account,input,s,before,now);
        String digest=canonicalHash(obj("inputHash",o.draft,"dependencies",deps,"impact",stable(impact)));
        JsonNode eligibility=obj("applicability","NOT_APPLICABLE","unreturnedCount",null,"anomalyCount",null,"debtState","NOT_APPLICABLE","debtPolicyVersion",null,"debtGuardVersion",null,"coveredSources",List.of(),"asOf",null,"items",List.of());
        JsonNode preview=obj("previewDigest",digest,"inputHash",o.draft,"identity",identity(target),"policy",s.policy(),"sourceProofDigest",null,"impact",impact,"eligibility",eligibility,"canConfirm",blocks.isEmpty(),"blocks",blocks);
        String request=UUID.randomUUID().toString();
        changed(jdbc.update("INSERT INTO member_finance_request(id,account_id,operation_id,actor_id,operation_key,action,request_hash,policy_digest,preview_digest,input_json,dependencies_json,preview_json,state,created_at,expires_at) VALUES (?,?,?,?,?,'RECEIPT',?,?,?,?,?,?,'PREVIEWED',?,?)",bytes(request),account.id(),bytes(o.id),o.actor,bytes(o.key),o.draft,s.policy().path("digest").asText(),digest,input.toString(),deps.toString(),preview.toString(),now,now.plusMinutes(10)));
        changed(jdbc.update("UPDATE member_finance_operation SET state='PREVIEWED',request_id=?,preview_digest=?,draft_input_json=NULL,updated_at=? WHERE id=? AND state='DRAFT_READY'",bytes(request),digest,now,bytes(o.id)));return success(op(o.actor,o.key,false));
    }
    Result confirm(Op o,ObjectNode b,User target,LocalDateTime now) {
        if(o.request==null)throw failure(409,"PREVIEW_REQUIRED");digest(o,b);Request r=req(o.request,true);JsonNode input=read(r.input);
        if(target==null||target.deleted!=0||!target.role.equals("READER"))return reject(o,"DEPENDENCIES_CHANGED",now);
        var account=read.account(o.member,input.path("fund").asText());var s=policy.snapshot(now);List<JsonNode> blocks=blocks(target,input,account,s,now,true);
        JsonNode before=read.balance(o.member,input.path("fund").asText(),now);
        if(!blocks.isEmpty())return reject(o,top(blocks.get(0)).equals("RULE_NOT_CONFIRMED")?"RULE_NOT_CONFIRMED":"DEPENDENCIES_CHANGED",now);
        if(!canonicalHash(read(r.dependencies)).equals(canonicalHash(dependencies(target,account,input,s,before,now))))return reject(o,"DEPENDENCIES_CHANGED",now);
        if(!read(r.preview).path("canConfirm").asBoolean())return reject(o,top(read(r.preview).path("blocks").get(0)),now);
        if(factExists(input))return reject(o,"BUSINESS_FACT_EXISTS",now);
        JsonNode impact=impact(before,input,s,now),payload=input.get("payload");
        long entry;
        // Catch only the global fact uniqueness collision, before any financial mutation.
        var connection=DataSourceUtils.getConnection(Objects.requireNonNull(jdbc.getDataSource()));Savepoint point;
        try {point=connection.setSavepoint();}catch(SQLException e){throw failure(500,"UNKNOWN_RESULT");}
        try {
            entry=insert("INSERT INTO member_finance_entry(account_id,request_id,ordinal,kind,amount,actual_at,method_code,root_fact_key,business_type,business_key,policy_digest,evidence_json,reason,operator_id,recorded_at) VALUES (?,?,1,'RECEIPT',?,?,?,?,'COUNTER_REGISTRATION',?,?,?,?,?,?)",account.id(),bytes(r.id),parseMoney(payload.get("amount")),FinanceCommands.time(payload.get("actualAt")),payload.path("methodCode").asText(),bytes(payload.path("factKey").asText()),bytes(payload.path("businessRef").path("id").asText()),r.policy,input.get("evidenceRefs").toString(),input.path("reason").asText(),o.actor,now);
        }catch(DuplicateKeyException e) {
            SQLException sql=null;for(Throwable cause=e;cause!=null;cause=cause.getCause())if(cause instanceof SQLException value)sql=value;
            if(!read.isMysql()||sql==null||sql.getErrorCode()!=1062||!sql.getMessage().contains("uk_fin_root_fact"))throw e;
            try {connection.rollback(point);connection.releaseSavepoint(point);}catch(SQLException x){throw failure(500,"UNKNOWN_RESULT");}
            if(!factExists(input))throw failure(500,"UNKNOWN_RESULT");return reject(o,"BUSINESS_FACT_EXISTS",now);
        }
        try {connection.releaseSavepoint(point);}catch(SQLException e){throw failure(500,"UNKNOWN_RESULT");}
        changed(jdbc.update("UPDATE member_finance_account SET revision=revision+1,scope_cutoff_at=? WHERE id=? AND revision=?",account.cutoff()==null?s.cutoff():account.cutoff(),account.id(),account.revision()));
        JsonNode after=read.balance(o.member,input.path("fund").asText(),now);
        long audit=insert("INSERT INTO member_finance_audit(request_id,account_id,operator_id,action,before_revision,after_revision,before_json,after_json,reason,recorded_at) VALUES (?,?,?,'RECEIPT',?,?,?,?,?,?)",bytes(r.id),account.id(),o.actor,account.revision(),account.revision()+1,before.toString(),after.toString(),input.path("reason").asText(),now);
        ObjectNode actual=jdbc.queryForObject("SELECT "+FinanceReadService.ENTRY_COLUMNS+" FROM member_finance_entry e WHERE e.id=?",read::entry,entry).value();
        actual.put("asOf",utc(now));actual.put("effectiveAtAsOf",true);actual.putNull("reversalEntryId");actual.set("allocations",JSON.createArrayNode());
        JsonNode auditView=obj("id",Long.toString(audit),"requestId",r.id,"accountId",Long.toString(account.id()),"operatorId",Long.toString(o.actor),"action","RECEIPT","beforeRevision",Long.toString(account.revision()),"afterRevision",Long.toString(account.revision()+1),"before",before,"after",after,"reason",input.get("reason"),"recordedAt",utc(now));
        JsonNode receipt=obj("requestId",r.id,"operationKey",o.key,"memberId",Long.toString(o.member),"accountId",Long.toString(account.id()),"fund",input.get("fund"),"currency","CNY","action","RECEIPT","confirmedAt",utc(now),"beforeRevision",Long.toString(account.revision()),"afterRevision",Long.toString(account.revision()+1),"entriesAtCommit",List.of(actual),"audit",auditView,"impact",impact,"previewDigest",r.digest,"policyDigest",r.policy,"entitlementEffect","NONE");
        terminal(o,"COMMITTED","COMMITTED",receipt,now);return success(op(o.actor,o.key,false));
    }
    Result reject(Op o,String code,LocalDateTime now) {
        terminal(o,"REJECTED",code,null,now);var result=refused(op(o.actor,o.key,false),code,code.equals("RULE_NOT_CONFIRMED")||code.equals("STORAGE_RANGE")?422:409);
        if(code.equals("BUSINESS_FACT_EXISTS"))result.failure.message(factMessage(read(req(o.request,false).input)));return result;
    }
}
