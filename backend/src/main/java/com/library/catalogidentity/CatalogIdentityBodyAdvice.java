package com.library.catalogidentity;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import java.io.*;
import java.lang.reflect.Type;
import static com.library.catalogattributes.CatalogAttributeSupport.*;

/** Preserve exact UTF-8 input and reject duplicate keys before the ordinary JsonNode converter. */
@ControllerAdvice(assignableTypes=CatalogIdentityController.class)
public class CatalogIdentityBodyAdvice extends RequestBodyAdviceAdapter {
    private static final ObjectMapper STRICT=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    @Override public boolean supports(MethodParameter p,Type t,Class<? extends HttpMessageConverter<?>> c){return p.getContainingClass()==CatalogIdentityController.class;}
    @Override public HttpInputMessage beforeBodyRead(HttpInputMessage message,MethodParameter p,Type t,Class<? extends HttpMessageConverter<?>> c)throws IOException{
        byte[] bytes=message.getBody().readAllBytes();try{STRICT.readTree(bytes);}catch(Exception e){throw fail(400,"INVALID_INPUT");}
        return new HttpInputMessage(){public InputStream getBody(){return new ByteArrayInputStream(bytes);}public HttpHeaders getHeaders(){return message.getHeaders();}};
    }
}
