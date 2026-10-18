package com.library.finance;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Test aliases only; MySQL uses guarded native JSON projection and SHA2. */
public final class FinanceH2Json {
    public static boolean valid(String s) {try{return s!=null&&FinanceSupport.JSON.readTree(s)!=null;}catch(Exception e){return false;}}
    public static String type(String s) {if(!valid(s))return null;JsonNode n=FinanceSupport.read(s);return n.isObject()?"OBJECT":n.isArray()?"ARRAY":n.isNull()?"NULL":n.isTextual()?"STRING":n.isIntegralNumber()?"INTEGER":n.isNumber()?"DOUBLE":"BOOLEAN";}
    public static String extract(String s,String path) {if(!valid(s))return null;JsonNode n=FinanceSupport.read(s).get(FinanceSupport.read(path.substring(2)).asText());return n==null?null:n.toString();}
    public static String unquote(String s) {if(s==null)return null;JsonNode n=FinanceSupport.read(s);return n.isTextual()?n.textValue():n.toString();}
    public static String sha(String s,int bits)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
    public static Integer length(String s) {return s==null?null:s.codePointCount(0,s.length());}
}
