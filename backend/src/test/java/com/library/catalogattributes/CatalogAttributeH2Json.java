package com.library.catalogattributes;
import com.fasterxml.jackson.databind.JsonNode;
import static com.library.catalogattributes.CatalogAttributeSupport.*;
/** Only H2 SQL aliases for fixed-path integration; production uses native MySQL. */
public final class CatalogAttributeH2Json {
    private CatalogAttributeH2Json(){}
    public static boolean valid(String s){try{return s!=null&&JSON.readTree(s)!=null;}catch(Exception e){return false;}}
    public static String type(String s){if(!valid(s))return null;JsonNode n=read(s);return n.isObject()?"OBJECT":n.isArray()?"ARRAY":n.isNull()?"NULL":n.isTextual()?"STRING":n.isIntegralNumber()?"INTEGER":n.isNumber()?"DOUBLE":"BOOLEAN";}
    static JsonNode cell(String s,String path){if(!valid(s))return null;String key=path.substring(2);if(key.startsWith("\""))key=read(key).textValue();return read(s).get(key);}
    public static String extract(String s,String path){JsonNode n=cell(s,path);return n==null?null:write(n);}
    public static boolean contains(String s,String mode,String path){return cell(s,path)!=null;}
}
