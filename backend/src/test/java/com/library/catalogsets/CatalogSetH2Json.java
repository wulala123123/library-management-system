package com.library.catalogsets;
import static com.library.catalogattributes.CatalogAttributeSupport.*;
public final class CatalogSetH2Json {
    public static String valueHash(String s){return s==null?null:hash(read(s));}
    public static String unquote(String s){if(s==null)return null;var n=read(s);return n.isTextual()?n.textValue():s;}
}
