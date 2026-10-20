package com.library.finance;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import static com.library.finance.FinanceSupport.*;

/** Literal protocol decoder. Validation errors never contain caller input. */
final class FinanceCommands {
    private FinanceCommands() {}
    private static final ObjectMapper STRICT=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static ObjectNode decode(HttpServletRequest request,String command) {
        if(request.getContentType()==null||!request.getContentType().matches("(?i)application/json(?:\\s*;\\s*charset=\\s*\"?utf-8\"?)?"))throw failure(400,"INVALID_INPUT");
        try {
            if(request.getContentLengthLong()>65536)throw failure(400,"INVALID_INPUT");
            byte[] raw=request.getInputStream().readNBytes(65537);if(raw.length>65536)throw failure(400,"INVALID_INPUT");
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
            JsonNode node=STRICT.readTree(text);if(node==null)throw failure(400,"INVALID_JSON");unicode(node);
            return validate(node,command);
        } catch(Failure e) {throw e;}
        catch(com.fasterxml.jackson.core.JsonParseException e) {throw failure(400,e.getOriginalMessage().startsWith("Duplicate field")?"DUPLICATE_FIELD":"INVALID_JSON");}
        catch(Exception e) {throw failure(400,"INVALID_JSON");}
    }
    static void unicode(JsonNode node) {
        if(node.isTextual())unicodeText(node.textValue());
        else if(node.isContainerNode()) {if(node.isObject())node.fieldNames().forEachRemaining(FinanceCommands::unicodeText);node.forEach(FinanceCommands::unicode);}
    }
    static void unicodeText(String s) {
        for(int i=0;i<s.length();i++) {char c=s.charAt(i);if(Character.isHighSurrogate(c)) {if(++i>=s.length()||!Character.isLowSurrogate(s.charAt(i)))throw failure(400,"INVALID_JSON");}else if(Character.isLowSurrogate(c))throw failure(400,"INVALID_JSON");}
    }
    static ObjectNode shape(JsonNode n,String... names) {
        if(n==null||!n.isObject())throw failure(400,"INVALID_INPUT");Set<String> fields=Set.of(names);
        n.fieldNames().forEachRemaining(k->{if(!fields.contains(k))throw failure(400,"UNKNOWN_FIELD");});
        for(String field:names)if(!n.has(field))throw failure(400,"INVALID_INPUT");return (ObjectNode)n;
    }
    static String string(JsonNode n,int min,int max) {
        if(n==null||!n.isTextual())throw failure(400,"INVALID_INPUT");String s=n.textValue();int length=s.codePointCount(0,s.length());
        if(length<min||length>max)throw failure(400,"INVALID_INPUT");return s;
    }
    static String pattern(JsonNode n,String regex) {String s=string(n,1,1000);if(!s.matches(regex))throw failure(400,"INVALID_INPUT");return s;}
    static String uuid(JsonNode n) {return pattern(n,"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");}
    static String key(JsonNode n) {return pattern(n,"[A-Za-z0-9_-]{32,128}");}
    static String digest(JsonNode n) {return pattern(n,"[0-9a-f]{64}");}
    static long number(JsonNode n,boolean zero) {
        String s=pattern(n,zero?"(?:0|[1-9][0-9]{0,18})":"[1-9][0-9]{0,18}");
        try{return Long.parseLong(s);}catch(NumberFormatException e){throw failure(400,"INVALID_INPUT");}
    }
    static void one(JsonNode n,String... values) {if(!Set.of(values).contains(string(n,1,1000)))throw failure(400,"INVALID_INPUT");}
    static void editor(JsonNode n,boolean zero) {shape(n,"editorId","epoch");uuid(n.get("editorId"));number(n.get("epoch"),zero);}
    static ObjectNode validate(JsonNode n,String c) {
        String[] fields=switch(c) {
            case "reserve"->new String[]{"schemaVersion","operationKey","memberId","editorId"};
            case "claim"->new String[]{"schemaVersion","operationKey","memberId","editorId","expectedEpoch","claimKey"};
            case "draft"->new String[]{"schemaVersion","operationKey","editor","input"};
            case "preview"->new String[]{"schemaVersion","operationKey","editor","draftHash"};
            case "confirm"->new String[]{"schemaVersion","operationKey","editor","previewDigest"};
            case "cancel"->new String[]{"schemaVersion","operationKey","memberId","editor","cancelKey","previewDigest","reason"};
            case "resolve"->new String[]{"schemaVersion","operationKey","memberId"};default->throw failure(400,"INVALID_INPUT");};
        ObjectNode out=shape(n,fields);if(!n.get("schemaVersion").isIntegralNumber()||!n.get("schemaVersion").canConvertToInt()||n.get("schemaVersion").intValue()!=1)throw failure(400,"INVALID_INPUT");
        key(n.get("operationKey"));if(n.has("memberId"))number(n.get("memberId"),false);if(n.has("editorId"))uuid(n.get("editorId"));
        if(n.has("editor"))editor(n.get("editor"),c.equals("cancel"));
        switch(c) {
            case "claim"->{number(n.get("expectedEpoch"),false);uuid(n.get("claimKey"));}
            case "draft"->out.set("input",input(n.get("input")));
            case "preview"->digest(n.get("draftHash"));
            case "confirm"->digest(n.get("previewDigest"));
            case "cancel"->{uuid(n.get("cancelKey"));if(!n.get("previewDigest").isNull())digest(n.get("previewDigest"));one(n.get("reason"),"USER_CANCELLED","RESTART_AFTER_UNKNOWN","ABANDON_UNSAVED_DRAFT");}
        }
        return (ObjectNode)canonical(out);
    }
    static JsonNode input(JsonNode n) {
        shape(n,"memberId","memberRevision","financeRevision","fund","currency","reason","evidenceRefs","action","payload");
        number(n.get("memberId"),false);number(n.get("memberRevision"),true);number(n.get("financeRevision"),true);one(n.get("fund"),"MEMBER_FEE","DEPOSIT");one(n.get("currency"),"CNY");
        String reason=string(n.get("reason"),1,1000);if(space(reason.codePointAt(0))||space(reason.codePointBefore(reason.length())))throw failure(400,"INVALID_INPUT");
        JsonNode refs=n.get("evidenceRefs");if(!refs.isArray()||refs.isEmpty()||refs.size()>20)throw failure(400,"INVALID_INPUT");
        for(JsonNode e:refs) {shape(e,"type","reference","digest");one(e.get("type"),"LEGACY_ARCHIVE","COUNTER_VOUCHER","VERIFICATION_NOTE");string(e.get("reference"),1,128);if(!e.get("digest").isNull())digest(e.get("digest"));}
        one(n.get("action"),"RECEIPT","OPENING","REFUND","CORRECT");
        // The other actions remain explicitly disabled in this deployment batch.
        if(!n.get("action").asText().equals("RECEIPT"))throw failure(422,"RULE_NOT_CONFIRMED");
        JsonNode p=n.get("payload");shape(p,"factKey","amount","actualAt","methodCode","paymentState","businessRef");uuid(p.get("factKey"));
        var amount=parseMoney(p.get("amount"));if(amount.signum()<=0)throw failure(400,"INVALID_INPUT");
        LocalDateTime actual=time(p.get("actualAt"));pattern(p.get("methodCode"),"[A-Z][A-Z0-9_]{0,31}");one(p.get("paymentState"),"RECORDED");
        JsonNode b=p.get("businessRef");shape(b,"type","id");one(b.get("type"),"COUNTER_REGISTRATION");uuid(b.get("id"));if(!b.get("id").equals(p.get("factKey")))throw failure(400,"INVALID_INPUT");
        ObjectNode copy=n.deepCopy();((ObjectNode)copy.get("payload")).put("amount",money(amount)).put("actualAt",utc(actual));return canonical(copy);
    }
    static boolean space(int point){return Character.isWhitespace(point)||Character.isSpaceChar(point)||point==0xfeff;}
    static LocalDateTime time(JsonNode n) {
        String s=pattern(n,"[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{1,6})?(?:Z|[+-](?:0[0-9]|1[0-3]):[0-5][0-9]|[+-]14:00)");
        if(s.length()>32)throw failure(400,"INVALID_INPUT");
        try{var value=OffsetDateTime.parse(s).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();if(value.getYear()<1000||value.getYear()>9999)throw failure(422,"STORAGE_RANGE");return value;}catch(DateTimeException e){throw failure(400,"INVALID_INPUT");}
    }
    /** RFC8785 ordering (UTF-16 key order); these schemas have no nonintegral JSON numbers. */
    static JsonNode canonical(JsonNode n) {
        if(n.isObject()) {ObjectNode o=JSON.createObjectNode();TreeSet<String> keys=new TreeSet<>();n.fieldNames().forEachRemaining(keys::add);for(String k:keys)o.set(k,canonical(n.get(k)));return o;}
        if(n.isArray()) {ArrayNode a=JSON.createArrayNode();n.forEach(v->a.add(canonical(v)));return a;}return n;
    }
    static String canonicalHash(JsonNode n) {return hash(canonical(n));}
}
