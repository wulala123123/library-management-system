package com.library.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Exact external JSON shape validation, separate from the frozen V1 deserializers. */
public final class LocationInput {
    private LocationInput() {}
    public static void keys(JsonNode node,String required,String optional) {
        if(node==null||!node.isObject()) invalid();
        Set<String> need=new HashSet<>(required.isEmpty()?List.of():List.of(required.split(",")));
        Set<String> allowed=new HashSet<>(need);if(!optional.isEmpty())allowed.addAll(List.of(optional.split(",")));
        node.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))invalid();});
        for(String key:need)if(!node.has(key))invalid();
    }
    public static long number(JsonNode node,boolean positive) {
        if(node==null||!node.isIntegralNumber()||!node.canConvertToLong())invalid();
        long value=node.longValue();if(value<(positive?1:0))invalid();return value;
    }
    public static int integer(JsonNode node,int min,int max) {
        if(node==null||!node.isIntegralNumber()||!node.canConvertToInt())invalid();int n=node.intValue();if(n<min||n>max)invalid();return n;
    }
    public static String text(JsonNode node,int max,boolean required) {
        if(node==null||!node.isTextual())invalid();String value=node.textValue();
        try{IntakeCatalog.text(value,max,required);}catch(InventoryIntakeException e){invalid();}return value;
    }
    public static String word(JsonNode node,String... allowed){String value=text(node,64,true);if(!List.of(allowed).contains(value))invalid();return value;}
    public static BookCopyActionException failure(int status,String code,String message){return new BookCopyActionException(status,code,message,null,null,null);}
    public static void invalid(){throw failure(400,"INVALID_INPUT","位置参数不符合要求");}
}
