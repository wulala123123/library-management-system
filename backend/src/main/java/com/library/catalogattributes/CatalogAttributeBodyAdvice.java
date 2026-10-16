package com.library.catalogattributes;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.library.controller.BookController;
import com.library.dto.BookRequest;
import java.io.*;
import java.lang.reflect.Type;
import java.util.Set;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import static com.library.catalogattributes.CatalogAttributeSupport.*;

/** Strict new input and explicit rejection at the legacy boundary without editing its DTO. */
@ControllerAdvice(assignableTypes={CatalogAttributeController.class,BookController.class})
public class CatalogAttributeBodyAdvice extends RequestBodyAdviceAdapter {
    private static final ObjectMapper STRICT=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    @Override public boolean supports(MethodParameter p,Type t,Class<? extends HttpMessageConverter<?>> c){return t==BookRequest.class||p.getContainingClass()==CatalogAttributeController.class;}
    @Override public HttpInputMessage beforeBodyRead(HttpInputMessage input,MethodParameter p,Type t,Class<? extends HttpMessageConverter<?>> c)throws IOException{
        byte[] bytes=input.getBody().readAllBytes();JsonNode node;
        try{node=STRICT.readTree(bytes);}catch(Exception e){throw fail(400,"INVALID_INPUT");}
        if(t==BookRequest.class&&node!=null&&node.isObject())for(String k:Set.of("displayTitle","displayTitleOverride","bindingCode","binding","ageLabels","age","adoptedSourceTitle","profile","catalogDigest"))if(node.has(k))invalid();
        return new HttpInputMessage(){public InputStream getBody(){return new ByteArrayInputStream(bytes);}public HttpHeaders getHeaders(){return input.getHeaders();}};
    }
}
