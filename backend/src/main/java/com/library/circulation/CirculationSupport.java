package com.library.circulation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;

import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Isolated B50 wire protocol. Text and barcode identities are never normalized. */
public final class CirculationSupport {
    private CirculationSupport() {}
    public static final long MAX = 9007199254740991L;
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                    .maxStringLength(4096).maxNumberLength(20).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public static final class Failure extends RuntimeException {
        public final int status; public final String reason;
        public Failure(int status, String reason) { super(reason); this.status=status; this.reason=reason; }
    }
    public static Failure fail(int status,String reason) { return new Failure(status,reason); }
    public static Failure conflict(String reason) { return fail(409,reason); }
    public static void invalid() { throw fail(400,"INVALID_INPUT"); }
    public static JsonNode tree(Object value) { return JSON.valueToTree(value); }
    public static ObjectNode obj(Object... values) {
        ObjectNode n=JSON.createObjectNode();
        for(int i=0;i<values.length;i+=2)n.set((String)values[i],tree(values[i+1]));
        return n;
    }
    public static String write(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch(Exception e) { throw fail(500,"CIRCULATION_SAVE_FAILED"); }
    }
    public static JsonNode read(String value) {
        try { return JSON.readTree(value); }
        catch(Exception e) { throw fail(500,"CIRCULATION_SAVE_FAILED"); }
    }
    public static JsonNode canonical(JsonNode n) {
        if(n.isObject()) { ObjectNode o=JSON.createObjectNode();TreeSet<String> names=new TreeSet<>();n.fieldNames().forEachRemaining(names::add);names.forEach(k->o.set(k,canonical(n.get(k))));return o; }
        if(n.isArray()) { ArrayNode a=JSON.createArrayNode();n.forEach(x->a.add(canonical(x)));return a; }
        return n.deepCopy();
    }
    public static String hash(JsonNode n) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(write(canonical(n)).getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw fail(500,"CIRCULATION_SAVE_FAILED"); }
    }
    public static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    public static void keys(JsonNode n,String expected) {
        Set<String> names=expected.isEmpty()?Set.of():new HashSet<>(Arrays.asList(expected.split(",")));
        if(n==null||!n.isObject()||n.size()!=names.size())invalid();
        for(String name:names)if(!n.has(name))invalid();
    }
    public static long number(JsonNode n) {
        if(n==null||!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<1||n.longValue()>MAX)invalid();
        return n.longValue();
    }
    public static long safe(long n) { if(n<1||n>MAX)throw conflict("UNSAFE_STORED_VALUE");return n; }
    public static String text(JsonNode n,int max) {
        if(n==null||!n.isTextual())invalid();String s=n.textValue();
        if(s.codePointCount(0,s.length())>max)invalid();
        for(int i=0;i<s.length();) {char c=s.charAt(i);if(Character.isSurrogate(c)&&(!Character.isHighSurrogate(c)||i+1>=s.length()||!Character.isLowSurrogate(s.charAt(i+1))))invalid();int cp=s.codePointAt(i);if(Character.isISOControl(cp))invalid();i+=Character.charCount(cp);}
        return s;
    }
    public static String key(String value) {
        if(value==null||!value.matches("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}"))invalid();
        return value;
    }
    public static String digest(JsonNode n) { String s=text(n,64);if(!s.matches("[a-f0-9]{64}"))invalid();return s; }
    public static long actor(Authentication a) {
        if(a==null||!a.isAuthenticated()||!(a.getPrincipal() instanceof Long id)||id<1||id>MAX)throw fail(403,"ROLE_REQUIRED");
        return id;
    }
    public static JsonNode body(HttpServletRequest request) {
        String type=request.getContentType();if(type==null||!type.split(";",2)[0].trim().equalsIgnoreCase("application/json"))invalid();
        try { byte[] b=request.getInputStream().readNBytes(32769);if(b.length>32768)throw fail(413,"BODY_TOO_LARGE");String utf8=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();JsonNode n=JSON.readTree(utf8);if(n==null)invalid();return n; }
        catch(Failure e){throw e;}catch(Exception e){invalid();return null;}
    }
    public static ObjectNode input(JsonNode n) {
        String op=text(n==null?null:n.get("operation"),6);
        if(op.equals("BORROW")) {
            keys(n,"operation,userId,items,remark");number(n.get("userId"));JsonNode a=n.get("items");
            if(!a.isArray()||a.isEmpty()||a.size()>10)invalid();Set<Long> books=new HashSet<>(),copies=new HashSet<>();
            for(JsonNode i:a) {keys(i,"bookId,copyId,borrowDays");if(!books.add(number(i.get("bookId")))||!copies.add(number(i.get("copyId"))))invalid();if(number(i.get("borrowDays"))>90)invalid();}
        } else if(op.equals("RETURN")) {
            keys(n,"operation,recordIds,remark");JsonNode a=n.get("recordIds");if(!a.isArray()||a.isEmpty()||a.size()>100)invalid();Set<Long> records=new HashSet<>();for(JsonNode i:a)if(!records.add(number(i)))invalid();
        } else invalid();
        text(n.get("remark"),500);return (ObjectNode)canonical(n);
    }
    public static void noQuery(Map<String,String[]> q) { if(!q.isEmpty())invalid(); }
    public static long queryNumber(String s) { if(s==null||!s.matches("[1-9][0-9]*"))invalid();try{return number(tree(Long.parseLong(s)));}catch(NumberFormatException e){invalid();return 0;} }
    public static LocalDateTime local(Instant i) { return LocalDateTime.ofInstant(i,ZoneOffset.UTC); }
    public static String utc(Object value) { return value==null?null:(value instanceof java.sql.Timestamp t?t.toLocalDateTime():(LocalDateTime)value).toInstant(ZoneOffset.UTC).toString(); }
}
