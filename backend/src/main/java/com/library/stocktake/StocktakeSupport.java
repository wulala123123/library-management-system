package com.library.stocktake;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** The stocktake protocol deliberately does not share legacy persistent request DTOs. */
public final class StocktakeSupport {
    private StocktakeSupport() {}
    public static final ObjectMapper JSON=new ObjectMapper();
    public static class Failure extends RuntimeException {
        public final int status; public final String code; public final Long sessionId; public final String entityKey;
        Failure(int status,String code,Long id,String entity){super(code);this.status=status;this.code=code;this.sessionId=id;this.entityKey=entity;}
    }
    public static Failure fail(int status,String code){return new Failure(status,code,null,null);}
    public static Failure fail(String code){return fail(409,code);}
    public static void invalid(){throw fail(400,"INVALID_INPUT");}
    public static ObjectNode obj(Object... pairs){ObjectNode n=JSON.createObjectNode();for(int i=0;i<pairs.length;i+=2)n.set((String)pairs[i],tree(pairs[i+1]));return n;}
    public static JsonNode tree(Object x){return x==null?NullNode.instance:x instanceof JsonNode?(JsonNode)x:JSON.valueToTree(x);}
    public static String write(Object x){try{return JSON.writeValueAsString(x);}catch(Exception e){throw new IllegalStateException(e);}}
    public static JsonNode read(String x){try{return JSON.readTree(x);}catch(Exception e){throw new IllegalStateException("Invalid stored stocktake evidence",e);}}
    public static JsonNode canonical(JsonNode n){if(n.isObject()){ObjectNode r=JSON.createObjectNode();TreeSet<String> names=new TreeSet<>();n.fieldNames().forEachRemaining(names::add);for(String s:names)r.set(s,canonical(n.get(s)));return r;}if(n.isArray()){ArrayNode r=JSON.createArrayNode();n.forEach(v->r.add(canonical(v)));return r;}return n;}
    public static String hash(Object x){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(write(canonical(tree(x))).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public static byte[] bytes(String s){return s==null?null:s.getBytes(StandardCharsets.UTF_8);}
    public static String b64(String s){return Base64.getEncoder().encodeToString(bytes(s));}
    public static String raw(Object x){return x==null?null:new String((byte[])x,StandardCharsets.UTF_8);}
    public static long num(Object x){return ((Number)x).longValue();}
    public static Long nullable(Object x){return x==null?null:num(x);}
    public static String now(){return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();}
    public static LocalDateTime local(String s){return s==null?null:LocalDateTime.ofInstant(Instant.parse(s),ZoneOffset.UTC);}
    public static String time(Object x){if(x==null)return null;if(x instanceof Timestamp t)return t.toLocalDateTime().toInstant(ZoneOffset.UTC).toString();if(x instanceof LocalDateTime t)return t.toInstant(ZoneOffset.UTC).toString();return x.toString();}
    public static void keys(JsonNode n,String names){if(n==null||!n.isObject())invalid();Set<String> expected=new HashSet<>(Arrays.asList(names.split(",")));if(n.size()!=expected.size())invalid();n.fieldNames().forEachRemaining(s->{if(!expected.contains(s))invalid();});for(String s:expected)if(!n.has(s))invalid();}
    public static long number(JsonNode n,boolean positive){if(n==null||!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<(positive?1:0))invalid();return n.longValue();}
    public static String text(JsonNode n,int max,boolean blank){if(n==null||!n.isTextual())invalid();String s=n.textValue();if(s.codePointCount(0,s.length())>max||s.isEmpty()||!blank&&s.isBlank())invalid();for(int i=0;i<s.length();){char c=s.charAt(i);if(Character.isSurrogate(c)&&(!Character.isHighSurrogate(c)||i+1>=s.length()||!Character.isLowSurrogate(s.charAt(i+1))))invalid();int cp=s.codePointAt(i);if(Character.isISOControl(cp))invalid();i+=Character.charCount(cp);}return s;}
    public static String word(JsonNode n,String... words){String s=text(n,64,false);if(!List.of(words).contains(s))invalid();return s;}
    public static String key(JsonNode n){String s=text(n,36,false);try{if(!UUID.fromString(s).toString().equalsIgnoreCase(s))invalid();}catch(IllegalArgumentException e){invalid();}return s.toLowerCase(Locale.ROOT);}
    public static String barcode(JsonNode n){String s=text(n,256,true);if(bytes(s).length>1024)invalid();return s;}
    public static boolean unsupported(String s){return s.codePointCount(0,s.length())>64||bytes(s).length>256;}
    public static List<JsonNode> array(JsonNode n,int max){if(n==null||!n.isArray()||n.isEmpty()||n.size()>max)invalid();List<JsonNode> a=new ArrayList<>();n.forEach(a::add);return a;}
    public static SortedSet<Long> ids(JsonNode n,int max){SortedSet<Long> a=new TreeSet<>();for(JsonNode x:array(n,max))if(!a.add(number(x,true)))invalid();return a;}
    public static SortedSet<Long> longs(JsonNode n){SortedSet<Long> a=new TreeSet<>();if(n!=null)for(JsonNode x:n)a.add(x.longValue());return a;}
    public static String placeholders(Collection<?> a){return String.join(",",Collections.nCopies(a.size(),"?"));}
    public static long insert(JdbcTemplate j,String sql,Object...args){KeyHolder k=new GeneratedKeyHolder();j.update(c->{PreparedStatement p=c.prepareStatement(sql,new String[]{"id"});p.setQueryTimeout(3);for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},k);return Objects.requireNonNull(k.getKey()).longValue();}
    public static void bound(int size,int max){if(size>max)throw fail("SCOPE_TOO_LARGE");}
    public static void scope(JsonNode n){String kind=word(n==null?null:n.get("kind"),"ADDRESS_SET","COPY_SET");String field=kind.equals("ADDRESS_SET")?"locationIds":"copyIds";keys(n,"kind,"+field);ids(n.get(field),field.equals("copyIds")?100:10);}
    public static void placement(JsonNode n){String kind=word(n==null?null:n.get("kind"),"UNASSIGNED","TEXT","REGISTERED");if(kind.equals("UNASSIGNED"))keys(n,"kind");else if(kind.equals("TEXT")){keys(n,"kind,text");text(n.get("text"),200,true);}else{keys(n,"kind,locationId,expectedAddressVersion");number(n.get("locationId"),true);JsonNode v=n.get("expectedAddressVersion");keys(v,"location,storey,bookshelf");for(String s:List.of("location","storey","bookshelf"))if(!v.get(s).isNull())number(v.get(s),false);}}
    public static void event(JsonNode e,boolean clientKey){keys(e,clientKey?"clientEventKey,rawBarcode,observedPlacement,clientObservedAt":"rawBarcode,observedPlacement,clientObservedAt");if(clientKey)key(e.get("clientEventKey"));barcode(e.get("rawBarcode"));placement(e.get("observedPlacement"));if(!e.get("clientObservedAt").isNull()){String s=text(e.get("clientObservedAt"),50,false);try{if(!s.endsWith("Z"))invalid();Instant.parse(s);}catch(Exception ex){invalid();}}}
    public static JsonNode validate(String op,JsonNode n){String base="operationKey,expectedRevision,reason";switch(op){
        case "CREATE"->{keys(n,"operationKey,scope,campaignKey,reason");scope(n.get("scope"));JsonNode c=n.get("campaignKey");if(!c.isNull()&&!(c.isTextual()&&c.textValue().isEmpty()))text(c,100,true);}
        case "OBSERVE"->{keys(n,base+",events");Set<String> a=new HashSet<>();for(JsonNode e:array(n.get("events"),100)){event(e,true);if(!a.add(key(e.get("clientEventKey"))))invalid();}}
        case "CORRECT"->{keys(n,base+",observationId,kind,replacement");number(n.get("observationId"),true);String k=word(n.get("kind"),"VOID","CORRECTION");if(k.equals("VOID")){if(!n.get("replacement").isNull())invalid();}else event(n.get("replacement"),false);}
        case "COVERAGE"->{keys(n,base+",items");Set<Long>a=new HashSet<>();for(JsonNode e:array(n.get("items"),100)){keys(e,"scopeItemId,status");if(!a.add(number(e.get("scopeItemId"),true)))invalid();word(e.get("status"),"NOT_ATTEMPTED","PARTIAL","COMPLETE");}}
        case "RECHECK"->{keys(n,base+",items");Set<String>a=new HashSet<>();for(JsonNode e:array(n.get("items"),1000)){keys(e,"entityKey,changeToken,expectedSignatureDigest,decision,evidence");if(!a.add(text(e.get("entityKey"),1500,false)))invalid();text(e.get("changeToken"),64,false);text(e.get("expectedSignatureDigest"),64,false);word(e.get("decision"),"OBSERVED_AGAIN","CHECKED_NOT_SEEN","LEGITIMATE_LOAN","CONFIRM_EXPLANATION");text(e.get("evidence"),500,false);}}
        case "PREPARE"->{keys(n,base+",completionKind");word(n.get("completionKind"),"PARTIAL","COMPLETE_SCOPE");}
        case "CONFIRM"->{keys(n,base+",reviewSnapshotId,previewDigest");number(n.get("reviewSnapshotId"),true);text(n.get("previewDigest"),64,false);}
        case "CHECK","CANCEL"->keys(n,base);
        case "RESOLVE"->{keys(n,"operationKey,sessionId,differenceIds,decision,workflow,candidateId,reason");number(n.get("sessionId"),true);ids(n.get("differenceIds"),100);String d=word(n.get("decision"),"OPEN","NO_ACTION_WITH_REASON","BUSINESS_REQUIRED");if(d.equals("BUSINESS_REQUIRED")){word(n.get("workflow"),"INTAKE","MOVE","WRITE_OFF","RESTORE","ENABLE");number(n.get("candidateId"),true);}else if(!n.get("workflow").isNull()||!n.get("candidateId").isNull())invalid();}
        case "LINK","REVOKE"->{keys(n,"operationKey,expectedVersion,reason");number(n.get("expectedVersion"),false);}
        default->throw new IllegalArgumentException(op);
    }key(n.get("operationKey"));text(n.get("reason"),500,false);if(n.has("expectedRevision"))number(n.get("expectedRevision"),false);ObjectNode result=n.deepCopy();result.put("operationKey",key(n.get("operationKey")));return canonical(result);}
}
