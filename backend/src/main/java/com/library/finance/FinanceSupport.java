package com.library.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** B40 protocol helpers. No financial input or archive content belongs in exceptions. */
public final class FinanceSupport {
    private FinanceSupport() {}
    static final ObjectMapper JSON = new ObjectMapper();
    static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");
    static final BigDecimal ZERO = new BigDecimal("0.00");
    public static final class Failure extends RuntimeException {
        final int status; final String code;
        JsonNode operation;JsonNode fieldErrors;String safeMessage;
        Failure(int status, String code) { super(code); this.status=status; this.code=code; }
        Failure withOperation(JsonNode value) { operation=value;return this; }
        Failure field(String path,String code) {fieldErrors=JSON.valueToTree(List.of(obj("path",path,"code",code)));return this;}
        Failure message(String value) {safeMessage=value;return this;}
    }
    static Failure failure(int status, String code) { return new Failure(status,code); }
    static ObjectNode obj(Object... pairs) {
        ObjectNode out=JSON.createObjectNode();
        for(int i=0;i<pairs.length;i+=2) out.set((String)pairs[i],JSON.valueToTree(pairs[i+1]));
        return out;
    }
    static JsonNode read(String value) {
        try { return JSON.readTree(value); } catch(Exception e) { throw failure(500,"INTERNAL_ERROR"); }
    }
    static String hash(JsonNode value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value))); }
        catch(Exception e) { throw failure(500,"INTERNAL_ERROR"); }
    }
    static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    static String text(byte[] value) { return value==null?null:new String(value,StandardCharsets.UTF_8); }
    static long id(String value, String error) {
        if(value==null||!value.matches("[1-9][0-9]{0,18}")) throw failure(400,error);
        try { return Long.parseLong(value); } catch(NumberFormatException e) { throw failure(400,error); }
    }
    static String money(BigDecimal value) {
        if(value==null) return null;
        try { return value.setScale(2,RoundingMode.UNNECESSARY).toPlainString(); }
        catch(ArithmeticException e) { throw failure(500,"INTERNAL_ERROR"); }
    }
    /** No JSON numbers, floating point or implicit rounding at the financial boundary. */
    public static BigDecimal parseMoney(JsonNode value) {
        if(value==null||!value.isTextual()) throw failure(400,"INVALID_INPUT");
        String s=value.textValue();
        if(s.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]{3,})")) throw failure(422,"MONEY_PRECISION");
        if(!s.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,2})?")) throw failure(400,"INVALID_INPUT");
        if(s.indexOf('.')<0?s.length()>15:s.indexOf('.')>15) throw failure(422,"STORAGE_RANGE");
        return new BigDecimal(s).setScale(2,RoundingMode.UNNECESSARY);
    }
    static String utc(LocalDateTime value) { return value==null?null:UTC.format(value); }
    static LocalDateTime utcInput(String value) {
        if(value==null||!value.matches("[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]\\.[0-9]{6}Z")) throw failure(400,"BAD_CURSOR");
        try { return LocalDateTime.ofInstant(Instant.parse(value),ZoneOffset.UTC); }
        catch(DateTimeException e) { throw failure(400,"BAD_CURSOR"); }
    }
    static ObjectNode known(BigDecimal n) { return obj("state","KNOWN","amount",money(n),"reasonCode",null); }
    static ObjectNode unknown(String reason) { return obj("state","UNKNOWN","amount",null,"reasonCode",reason); }
    public static ObjectNode envelope(JsonNode data) { return obj("schemaVersion",1,"code",200,"message","成功","data",data); }
    public static ObjectNode error(int status,String code) {
        return obj("schemaVersion",1,"code",status,"message","财务查询未完成，请核对权限或查询条件","data",
                obj("errorCode",code,"operationKey",null,"requestId",null,"state",null,
                    "certainty",status>=500?"UNKNOWN":"NO_FINANCIAL_WRITE","recovery","NONE","retryable",false,"fieldErrors",List.of(),"operation",null));
    }
    static ObjectNode error(Failure e) {
        ObjectNode out=error(e.status,e.code);
        if(e.safeMessage!=null&&e.status!=401&&e.status!=403)out.put("message",e.safeMessage);
        if(e.fieldErrors!=null&&e.status!=401&&e.status!=403)((ObjectNode)out.get("data")).set("fieldErrors",e.fieldErrors);
        if(e.operation!=null&&e.status!=401&&e.status!=403) {
            ObjectNode data=(ObjectNode)out.get("data");JsonNode op=e.operation;
            data.set("operation",op);data.set("operationKey",op.get("operationKey"));data.set("state",op.get("state"));
            data.set("requestId",op.path("request").path("requestId").isMissingNode()?JSON.nullNode():op.path("request").get("requestId"));
            boolean terminal=Set.of("COMMITTED","CANCELLED","EXPIRED","REJECTED").contains(op.path("state").asText());
            data.put("certainty",terminal?"SAVED_TERMINAL":"NO_FINANCIAL_WRITE");data.put("recovery",terminal?"REVIEW_TERMINAL":"READ_KEY");
        }
        if(e.status>=500) {((ObjectNode)out.get("data")).put("recovery","READ_KEY");((ObjectNode)out.get("data")).put("retryable",true);}
        return out;
    }
    public static boolean isReadPath(HttpServletRequest r) {
        String uri=r.getRequestURI(),context=r.getContextPath();
        return "GET".equals(r.getMethod())&&uri!=null&&context!=null&&uri.startsWith(context)
                &&uri.substring(context.length()).matches("/members/[^/]+/finance(?:/(?:source|entries))?");
    }
    /** Exact method/route membership; never bypass the global filter for a whole prefix. */
    public static boolean isFinancePath(HttpServletRequest r) {
        if(isReadPath(r))return true;
        String uri=r.getRequestURI(),context=r.getContextPath();
        if(uri==null||context==null||!uri.startsWith(context))return false;
        String p=uri.substring(context.length());
        return "GET".equals(r.getMethod())&&p.matches("/member-finance/(?:operations/(?:active|by-key/[^/]+)|requests/[^/]+)")
                ||"POST".equals(r.getMethod())&&p.matches("/member-finance/operations(?:/by-key/[^/]+/(?:claim|draft|preview|confirm|cancel|resolve))?");
    }
    public record TokenVersion(long value,Instant expiresAt) {}
    public static void securityError(HttpServletResponse response,int status,String code)throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        JSON.writeValue(response.getWriter(),error(status,code));
    }
}
