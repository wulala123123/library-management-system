package com.library.catalogattributes;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.*;
import org.springframework.security.core.Authentication;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Independent protocol: explicit nulls, stable ordered evidence, no legacy DTO changes. */
public final class CatalogAttributeSupport {
    private CatalogAttributeSupport() {}
    public static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    public static final List<String> AGES = List.of("AGE_0_2","AGE_3_4","AGE_5_6","AGE_7_9","AGE_10_12");
    public static final List<String> AGE_TEXT = List.of("0-2岁","3-4岁","5-6岁","7-9岁","10-12岁");
    public static final List<String> BINDINGS = List.of("BOARD","PAPERBACK","HARDCOVER");
    public static final List<String> FIELDS = List.of("displayTitleOverride","bindingCode","ageLabels","adoptedSourceTitle");
    public record Actor(long id, boolean admin, boolean librarian) {
        public void role() { if (id<=0 || !admin && !librarian) throw fail(403,"CATALOG_ROLE_REQUIRED"); }
        public void source() { role(); if (!admin) throw fail(403,"CATALOG_ROLE_REQUIRED"); }
        public void mode(String mode) { role(); if ("SOURCE".equals(mode)) source(); }
    }
    public static Actor actor(Authentication a) {
        if(a==null || !a.isAuthenticated()) throw fail(401,"CATALOG_ROLE_REQUIRED");
        if(!(a.getPrincipal() instanceof Long id)) throw fail(403,"CATALOG_ROLE_REQUIRED");
        return new Actor(id,a.getAuthorities().stream().anyMatch(x->x.getAuthority().equals("ROLE_ADMIN")),a.getAuthorities().stream().anyMatch(x->x.getAuthority().equals("ROLE_LIBRARIAN")));
    }
    public static class Failure extends RuntimeException {
        public final int status; public final String code;
        public Failure(int status,String code){super(code);this.status=status;this.code=code;}
    }
    public static Failure fail(int status,String code){return new Failure(status,code);}
    public static Failure conflict(String code){return fail(409,code);}
    public static void invalid(){throw fail(400,"INVALID_INPUT");}
    public static ObjectNode obj(Object... pairs){ObjectNode n=JSON.createObjectNode();for(int i=0;i<pairs.length;i+=2)n.set((String)pairs[i],tree(pairs[i+1]));return n;}
    public static JsonNode tree(Object x){return x==null?NullNode.instance:x instanceof JsonNode n?n:JSON.valueToTree(x);}
    public static String write(Object x){try{return JSON.writeValueAsString(x);}catch(Exception e){throw fail(500,"CATALOG_SAVE_FAILED");}}
    public static JsonNode read(String x){try{return JSON.readTree(x);}catch(Exception e){throw fail(500,"CATALOG_SAVE_FAILED");}}
    public static JsonNode canonical(JsonNode n){if(n.isObject()){ObjectNode r=JSON.createObjectNode();TreeSet<String> keys=new TreeSet<>();n.fieldNames().forEachRemaining(keys::add);keys.forEach(k->r.set(k,canonical(n.get(k))));return r;}if(n.isArray()){ArrayNode r=JSON.createArrayNode();n.forEach(x->r.add(canonical(x)));return r;}return n;}
    public static String hash(Object n){return sha(write(canonical(tree(n))));}
    public static String sha(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(s)));}catch(Exception e){throw fail(500,"CATALOG_SAVE_FAILED");}}
    public static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    public static long num(Object x){return ((Number)x).longValue();}
    public static Long nullable(Object x){return x==null?null:num(x);}
    public static String now(){return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();}
    public static LocalDateTime local(String s){return LocalDateTime.ofInstant(Instant.parse(s),ZoneOffset.UTC);}
    public static String time(Object x){if(x instanceof Timestamp t)return t.toLocalDateTime().toInstant(ZoneOffset.UTC).toString();if(x instanceof LocalDateTime t)return t.toInstant(ZoneOffset.UTC).toString();return x.toString();}
    public static void keys(JsonNode n,String names){Set<String> a=new HashSet<>(Arrays.asList(names.split(",")));if(n==null||!n.isObject()||n.size()!=a.size())invalid();for(String k:a)if(!n.has(k))invalid();n.fieldNames().forEachRemaining(k->{if(!a.contains(k))invalid();});}
    public static long number(JsonNode n,boolean positive){if(n==null||!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<(positive?1:0))invalid();return n.longValue();}
    public static String text(JsonNode n,int max){if(n==null||!n.isTextual()||!validText(n.textValue(),max))invalid();return n.textValue();}
    public static boolean validText(String s,int max){if(s==null||s.isEmpty()||s.codePointCount(0,s.length())>max||s.codePoints().allMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)))return false;for(int i=0;i<s.length();){char c=s.charAt(i);if(Character.isSurrogate(c)&&(!Character.isHighSurrogate(c)||i+1>=s.length()||!Character.isLowSurrogate(s.charAt(i+1))))return false;int cp=s.codePointAt(i);if(Character.isISOControl(cp)||Character.getType(cp)==Character.FORMAT)return false;i+=Character.charCount(cp);}return true;}
    public static String word(JsonNode n,List<String> allowed){String s=text(n,64);if(!allowed.contains(s))invalid();return s;}
    public static String key(JsonNode n){String s=text(n,36);if(!s.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))invalid();return s;}
    public static String digest(JsonNode n){String s=text(n,64);if(!s.matches("[a-f0-9]{64}"))invalid();return s;}
    public static ArrayNode ages(JsonNode n){if(n==null||!n.isArray()||n.isEmpty()||n.size()>5)invalid();Set<String> values=new HashSet<>();for(JsonNode x:n)if(!values.add(word(x,AGES)))invalid();ArrayNode a=JSON.createArrayNode();AGES.stream().filter(values::contains).forEach(a::add);return a;}
    public static String placeholders(int n){return String.join(",",Collections.nCopies(n,"?"));}
    public static long insert(JdbcTemplate j,String sql,Object...args){KeyHolder k=new GeneratedKeyHolder();j.update(c->{PreparedStatement p=c.prepareStatement(sql,new String[]{"id"});p.setQueryTimeout(3);for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},k);return Objects.requireNonNull(k.getKey()).longValue();}
}
