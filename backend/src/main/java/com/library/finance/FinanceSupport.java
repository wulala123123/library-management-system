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
        Failure(int status, String code) { super(code); this.status=status; this.code=code; }
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
    /** Strict CNY storage parser for future commands; this release exposes no write route. */
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
    public static boolean isReadPath(HttpServletRequest r) {
        String uri=r.getRequestURI(),context=r.getContextPath();
        return "GET".equals(r.getMethod())&&uri!=null&&context!=null&&uri.startsWith(context)
                &&uri.substring(context.length()).matches("/members/[^/]+/finance(?:/(?:source|entries))?");
    }
    public static void securityError(HttpServletResponse response,int status,String code)throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        JSON.writeValue(response.getWriter(),error(status,code));
    }
}
