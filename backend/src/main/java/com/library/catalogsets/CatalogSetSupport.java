package com.library.catalogsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import java.text.Normalizer;
import java.util.*;
import static com.library.catalogattributes.CatalogAttributeSupport.*;

/** The B34 wire protocol has its own strict types and never changes legacy JSON. */
public final class CatalogSetSupport {
    private CatalogSetSupport() {}
    public static final long MAX=9007199254740991L;
    public static final List<String> OPERATIONS=List.of("CREATE","UPDATE_METADATA","ADD_MEMBERS","RETIRE_MEMBERS","RESTORE_MEMBERS","RETIRE_SET","RESTORE_SET");
    public static final List<String> MARKERS=List.of("NO_SOURCE","CONFLICT","MISSING","NULL","EMPTY","WHITESPACE","UNSET","WRONG_TYPE","MEMBER_MARKER","UNMAPPED");
    public static final List<String> REASONS=List.of("MEMBER_MARKER_NO_PARENT","SOURCE_CONFLICT","ASSOCIATION_INVALID","UNMAPPED_MARKER","NO_SOURCE","INCOMPLETE_SOURCE_VALUE","SOURCE_LIMIT_EXCEEDED");
    public record Actor(long id,boolean admin,boolean librarian) {
        public void role(){if(id<1||id>MAX||!admin&&!librarian)throw failure(403,"CATALOG_SET_ROLE_REQUIRED");}
        public void maintain(){role();if(!admin)throw failure(403,"CATALOG_SET_ROLE_REQUIRED");}
    }
    public static Actor actor(Authentication a){if(a==null||!a.isAuthenticated()||!(a.getPrincipal() instanceof Long id))throw failure(403,"CATALOG_SET_ROLE_REQUIRED");return new Actor(id,a.getAuthorities().stream().anyMatch(x->x.getAuthority().equals("ROLE_ADMIN")),a.getAuthorities().stream().anyMatch(x->x.getAuthority().equals("ROLE_LIBRARIAN")));}
    public static class Failure extends RuntimeException {
        public final int status;public final String code;public Long requestId,setId,bookId,membershipId;
        Failure(int s,String c){super(c);status=s;code=c;}
    }
    public static Failure failure(int s,String c){return new Failure(s,c);}
    public static void invalid(){throw failure(400,"INVALID_INPUT");}
    public static void keys(JsonNode n,String names){Set<String> namesSet=new HashSet<>(Arrays.asList(names.split(",")));if(n==null||!n.isObject()||n.size()!=namesSet.size())invalid();for(String s:namesSet)if(!n.has(s))invalid();}
    public static long number(JsonNode n,boolean positive){if(n==null||!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<(positive?1:0)||n.longValue()>MAX)invalid();return n.longValue();}
    public static long generated(long id){if(id<1||id>MAX)throw failure(409,"REVISION_EXHAUSTED");return id;}
    public static long next(long n){if(n>=MAX)throw failure(409,"REVISION_EXHAUSTED");return n+1;}
    public static boolean whitespace(String s){return s.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c));}
    public static String text(JsonNode n,int max,boolean empty,boolean lines){if(n==null||!n.isTextual())invalid();String s=n.textValue();if(s.codePointCount(0,s.length())>max||!empty&&(s.isEmpty()||whitespace(s)))invalid();for(int i=0;i<s.length();){char c=s.charAt(i);if(Character.isSurrogate(c)&&(!Character.isHighSurrogate(c)||i+1>=s.length()||!Character.isLowSurrogate(s.charAt(i+1))))invalid();int cp=s.codePointAt(i);if(Character.isISOControl(cp)&&!(lines&&(cp==9||cp==10||cp==13)))invalid();i+=Character.charCount(cp);}return s;}
    public static String key(JsonNode n){String s=text(n,36,false,false);if(!s.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))invalid();return s;}
    public static String digest(JsonNode n){String s=text(n,64,false,false);if(!s.matches("[0-9a-f]{64}"))invalid();return s;}
    public static String word(JsonNode n,List<String> words){String s=text(n,64,false,false);if(!words.contains(s))invalid();return s;}
    public static void evidence(JsonNode n){keys(n,"kind,reference");word(n.get("kind"),List.of("COPYRIGHT_PAGE","PUBLISHER_MATERIAL","LEGACY_RECORD","MANUAL_REVIEW"));text(n.get("reference"),1000,false,false);}
    public static String normalized(String s){String n=Normalizer.normalize(s,Normalizer.Form.NFKC).trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);if(n.codePointCount(0,n.length())>3600)invalid();return n;}
    public static ObjectNode validate(JsonNode n){String op=word(n==null?null:n.get("operation"),OPERATIONS);String head="schemaVersion,operationKey,mode,reason,operation";keys(n,head+switch(op){case "CREATE"->",clientRef,name,note,evidence,members";case "UPDATE_METADATA"->",setId,expectedSet,changes,evidence";case "ADD_MEMBERS","RETIRE_MEMBERS","RESTORE_MEMBERS"->",setId,expectedSet,members";default->",setId,expectedSet,evidence";});if(number(n.get("schemaVersion"),true)!=1)invalid();key(n.get("operationKey"));word(n.get("mode"),List.of("MANUAL"));text(n.get("reason"),500,false,false);
        if(op.equals("CREATE")){String ref=text(n.get("clientRef"),64,false,false);if(!ref.matches("[A-Za-z0-9_-]{1,64}"))invalid();normalized(text(n.get("name"),200,false,false));if(!n.get("note").isNull())text(n.get("note"),2000,true,true);}else{number(n.get("setId"),true);expected(n.get("expectedSet"),false);}
        if(n.has("evidence"))evidence(n.get("evidence"));
        if(op.equals("UPDATE_METADATA")){JsonNode changes=n.get("changes");keys(changes,"name,note");boolean changed=false;for(String f:List.of("name","note")){JsonNode c=changes.get(f);String action=word(c.get("action"),f.equals("name")?List.of("KEEP","SET"):List.of("KEEP","SET","CLEAR"));keys(c,action.equals("SET")?"action,value":"action");if(!action.equals("KEEP"))changed=true;if(action.equals("SET")){String s=text(c.get("value"),f.equals("name")?200:2000,f.equals("note"),f.equals("note"));if(f.equals("name"))normalized(s);}}if(!changed)invalid();}
        ObjectNode result=n.deepCopy();if(n.has("members")){JsonNode rows=n.get("members");if(!rows.isArray()||rows.size()>100||rows.isEmpty()&&!op.equals("CREATE"))invalid();TreeMap<Long,JsonNode> sorted=new TreeMap<>();Set<Long> ids=new HashSet<>();boolean existing=op.equals("RETIRE_MEMBERS")||op.equals("RESTORE_MEMBERS");for(JsonNode row:rows){keys(row,existing?"membershipId,bookId,expectedMember,expectedBook,evidence":"bookId,expectedBook,evidence");long id=number(row.get("bookId"),true);if(sorted.put(id,row)!=null)invalid();expected(row.get("expectedBook"),true);evidence(row.get("evidence"));if(existing){if(!ids.add(number(row.get("membershipId"),true)))invalid();expected(row.get("expectedMember"),false);}}result.set("members",tree(sorted.values()));}return (ObjectNode)canonical(result);
    }
    private static void expected(JsonNode n,boolean book){keys(n,book?"revision,attributeRevision,digest,sourceDigest":"revision,digest");number(n.get("revision"),false);digest(n.get("digest"));if(book){number(n.get("attributeRevision"),false);digest(n.get("sourceDigest"));}}
    public static void confirmation(JsonNode n,boolean cancel){keys(n,cancel?"schemaVersion,operationKey,previewDigest,reason":"schemaVersion,operationKey,previewDigest");if(number(n.get("schemaVersion"),true)!=1)invalid();key(n.get("operationKey"));digest(n.get("previewDigest"));if(cancel)word(n.get("reason"),List.of("EDIT","ABANDON"));}
    public static long queryNumber(String s,boolean positive){if(s==null||!s.matches("[0-9]+"))invalid();try{return number(JSON.getNodeFactory().numberNode(Long.parseLong(s)),positive);}catch(NumberFormatException e){invalid();return 0;}}
    public static final class Query {
        public final MultiValueMap<String,String> values;public final int page,size;public final long offset;
        public Query(MultiValueMap<String,String> values,String allowed,String lists){this.values=values;Set<String> a=new HashSet<>(Arrays.asList(("page,size,"+allowed).split(","))),l=new HashSet<>(Arrays.asList(lists.split(",")));values.forEach((k,v)->{if(!a.contains(k)||v==null||v.isEmpty()||!l.contains(k)&&v.size()!=1||new HashSet<>(v).size()!=v.size())invalid();});long p=queryNumber(one("page","1"),true),s=queryNumber(one("size","20"),true);if(p>Integer.MAX_VALUE||s>100)invalid();page=(int)p;size=(int)s;offset=(p-1)*s;}
        public String one(String k,String fallback){return values.containsKey(k)?values.getFirst(k):fallback;}
        public String choice(String k,String fallback,List<String> options){return word(tree(one(k,fallback)),options);}
        public List<String> many(String k,List<String> options){List<String> vals=values.getOrDefault(k,List.of());for(String v:vals)word(tree(v),options);return vals;}
        public String keyword(){return text(tree(one("keyword","")),200,true,false);}
    }
    public static ObjectNode page(JsonNode records,long total,Query q){return obj("schemaVersion",1,"records",records,"total",total,"page",q.page,"size",q.size,"pages",(total+q.size-1)/q.size);}
    public static String like(String s){return "%"+s.replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
}
