package com.library.catalogpublication;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import java.io.*;
import java.lang.reflect.Type;

@ControllerAdvice(assignableTypes=PublicationController.class)
public class PublicationBodyAdvice extends RequestBodyAdviceAdapter {
    private static final ObjectMapper STRICT=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    @Override public boolean supports(MethodParameter p,Type t,Class<? extends HttpMessageConverter<?>> c){return p.getContainingClass()==PublicationController.class;}
    @Override public HttpInputMessage beforeBodyRead(HttpInputMessage m,MethodParameter p,Type t,Class<? extends HttpMessageConverter<?>> c)throws IOException{byte[] b=m.getBody().readAllBytes();try{if(STRICT.readTree(b)==null)PublicationSupport.invalid();}catch(Exception e){PublicationSupport.invalid();}return new HttpInputMessage(){public InputStream getBody(){return new ByteArrayInputStream(b);}public HttpHeaders getHeaders(){return m.getHeaders();}};}
}
