package com.library.catalogsets;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import java.io.*;
import java.lang.reflect.Type;
import static com.library.catalogsets.CatalogSetSupport.*;

@Configuration
class CatalogSetWeb implements WebMvcConfigurer {
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(new HandlerInterceptor(){
        @Override public boolean preHandle(HttpServletRequest r,HttpServletResponse s,Object h){Actor a=actor(SecurityContextHolder.getContext().getAuthentication());String p=r.getRequestURI().substring(r.getContextPath().length());boolean safe=r.getMethod().equals("GET")&&(p.equals("/catalog-sets")||p.matches("/catalog-sets/[0-9]+(?:/members)?")||p.matches("/catalog-sets/books/[0-9]+/memberships"));if(safe)a.role();else a.maintain();return true;}
    }).addPathPatterns("/catalog-sets","/catalog-sets/**","/catalog-set-reviews","/catalog-set-reviews/**");}
}

@ControllerAdvice(assignableTypes=CatalogSetController.class)
class CatalogSetBodyAdvice extends RequestBodyAdviceAdapter {
    private static final ObjectMapper STRICT=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    @Override public boolean supports(MethodParameter p,Type t,Class<? extends HttpMessageConverter<?>> c){return p.getContainingClass()==CatalogSetController.class;}
    @Override public HttpInputMessage beforeBodyRead(HttpInputMessage m,MethodParameter p,Type t,Class<? extends HttpMessageConverter<?>> c)throws IOException{byte[] b=m.getBody().readAllBytes();try{if(STRICT.readTree(b)==null)invalid();}catch(Exception e){invalid();}return new HttpInputMessage(){public InputStream getBody(){return new ByteArrayInputStream(b);}public HttpHeaders getHeaders(){return m.getHeaders();}};}
}
