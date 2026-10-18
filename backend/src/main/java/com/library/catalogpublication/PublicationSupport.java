package com.library.catalogpublication;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.Authentication;
import java.time.LocalDate;
import java.util.*;

/** Strict publication protocol, independent of legacy DTO serialization. */
public final class PublicationSupport {
    private PublicationSupport() {}
    public static final List<String> FIELDS=List.of("publishDate","pages","description");
    public record Actor(long id,boolean admin,boolean librarian) {
        public void role(){if(id<=0||!admin&&!librarian)throw failure(403,"CATALOG_PUBLICATION_ROLE_REQUIRED");}
        public void batch(int count){role();if(count>1&&!admin)throw failure(403,"CATALOG_PUBLICATION_ROLE_REQUIRED");}
    }
    public static Actor actor(Authentication a){if(a==null||!a.isAuthenticated()||!(a.getPrincipal() instanceof Long id))throw failure(403,"CATALOG_PUBLICATION_ROLE_REQUIRED");return new Actor(id,a.getAuthorities().stream().anyMatch(x->x.getAuthority().equals("ROLE_ADMIN")),a.getAuthorities().stream().anyMatch(x->x.getAuthority().equals("ROLE_LIBRARIAN")));}
    public static class Failure extends RuntimeException {
        public final int status;public final String code;public Long requestId,bookId;
        Failure(int status,String code){super(code);this.status=status;this.code=code;}
        public Failure context(Long request,Long book){if(requestId==null)requestId=request;if(bookId==null)bookId=book;return this;}
    }
    public static Failure failure(int status,String code){return new Failure(status,code);}
    public static Failure changed(){return failure(409,"PUBLICATION_CHANGED");}
    public static void invalid(){throw failure(400,"INVALID_INPUT");}
    public static void keys(JsonNode n,String names){Set<String> expected=new HashSet<>(Arrays.asList(names.split(",")));if(n==null||!n.isObject()||n.size()!=expected.size())invalid();for(String k:expected)if(!n.has(k))invalid();}
    public static long number(JsonNode n,boolean positive){if(n==null||!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<(positive?1:0))invalid();return n.longValue();}
    public static String text(JsonNode n,int max,boolean empty,boolean lines){if(n==null||!n.isTextual())invalid();String s=n.textValue();if(s.codePointCount(0,s.length())>max||!empty&&(s.isEmpty()||s.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c))))invalid();for(int i=0;i<s.length();){char c=s.charAt(i);if(Character.isSurrogate(c)&&(!Character.isHighSurrogate(c)||i+1>=s.length()||!Character.isLowSurrogate(s.charAt(i+1))))invalid();int cp=s.codePointAt(i);if(Character.isISOControl(cp)&&!(lines&&(cp==9||cp==10||cp==13)))invalid();i+=Character.charCount(cp);}return s;}
    public static String key(JsonNode n){String s=text(n,36,false,false);if(!s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))invalid();return s;}
    public static String digest(JsonNode n){String s=text(n,64,false,false);if(!s.matches("[0-9a-f]{64}"))invalid();return s;}
    public static String word(JsonNode n,List<String> words){String s=text(n,64,false,false);if(!words.contains(s))invalid();return s;}
    public static String date(JsonNode n){String s=text(n,10,false,false);if(!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))invalid();try{LocalDate d=LocalDate.parse(s);if(d.getYear()<1)invalid();}catch(RuntimeException e){invalid();}return s;}
}
