package com.library;

import com.library.service.StatisticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.ActiveProfiles;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:copy-projection-query-count;DB_CLOSE_DELAY=-1;MODE=MYSQL")
@ActiveProfiles("test") @Import(BookCopyActionServiceTest.CountingConfiguration.class)
class BookCopyActionServiceTest {
    @Autowired StatisticsService stats;@Autowired JdbcTemplate jdbc;
    static final AtomicInteger SELECTS=new AtomicInteger(),METADATA=new AtomicInteger();
    @Test void completeCatalogUsesConstantDataQueriesAndCachedDialectWithoutCancellingPerTitleDifferences(){
        addTitles(4);stats.getSummary();SELECTS.set(0);METADATA.set(0);long smallStart=System.nanoTime();var small=stats.getSummary();long smallMs=(System.nanoTime()-smallStart)/1_000_000;assertEquals(5,SELECTS.get());assertEquals(0,METADATA.get());
        addTitles(200);SELECTS.set(0);METADATA.set(0);long start=System.nanoTime();var large=stats.getSummary();long largeMs=(System.nanoTime()-start)/1_000_000;assertEquals(5,SELECTS.get(),"Summary statements must not grow per title");assertEquals(0,METADATA.get());assertEquals(small.getCatalogTitles()+200,large.getCatalogTitles());
        String positive="gap-positive";jdbc.update("INSERT INTO book(isbn,title,stock_total,stock_available) VALUES (?,'合成正差额',1,0)",positive);long plus=jdbc.queryForObject("SELECT id FROM book WHERE isbn=?",Long.class,positive);
        jdbc.update("INSERT INTO book(isbn,title,stock_total,stock_available) VALUES ('gap-negative','合成负差额',0,0)");long minus=jdbc.queryForObject("SELECT id FROM book WHERE isbn='gap-negative'",Long.class);jdbc.update("INSERT INTO book_copy(book_id,barcode,status) VALUES (?,'gap-unknown','原未知')",minus);
        SELECTS.set(0);METADATA.set(0);var reconciliation=stats.getReconciliation();assertEquals(4,SELECTS.get());assertEquals(0,METADATA.get());
        var p=reconciliation.getItems().stream().filter(i->i.getBookId()==plus).findFirst().orElseThrow().getReconciliationV2();var n=reconciliation.getItems().stream().filter(i->i.getBookId()==minus).findFirst().orElseThrow().getReconciliationV2();assertEquals(1,p.inStockDifference());assertEquals(-1,n.inStockDifference());var opposite=com.library.service.CopyInventoryProjection.sum(List.of(p,n));assertEquals(0,opposite.inStockDifference());assertTrue(opposite.hasHistoricalDifference(),"Opposite title differences must not cancel their flag");assertTrue(reconciliation.getReconciliationV2().hasHistoricalDifference());
        SELECTS.set(0);stats.exportCsv();assertEquals(5,SELECTS.get());
        System.out.printf("COPY_PROJECTION_QUERY_COUNT smallAdded=4 summarySelects=5 smallMs=%d largeAdded=204 summarySelects=5 largeMs=%d reconciliationSelects=4 csvSelects=5 dialectMetadataAfterWarmup=0%n",smallMs,largeMs);
    }
    private void addTitles(int amount){for(int n=0;n<amount;n++){String isbn=UUID.randomUUID().toString().substring(0,20);jdbc.update("INSERT INTO book(isbn,title,stock_total,stock_available) VALUES (?,'合成批量投影',1,1)",isbn);long id=jdbc.queryForObject("SELECT id FROM book WHERE isbn=?",Long.class,isbn);jdbc.update("INSERT INTO book_copy(book_id,barcode,status) VALUES (?,?,'AVAILABLE')",id,"batch-"+UUID.randomUUID());}}
    @TestConfiguration(proxyBeanMethods=false) static class CountingConfiguration{
        @Bean static BeanPostProcessor countDataSource(){return new BeanPostProcessor(){@Override public Object postProcessAfterInitialization(Object bean,String name){if(bean instanceof DataSource source&&name.equals("dataSource"))return new DelegatingDataSource(source){@Override public Connection getConnection()throws SQLException{return connection(super.getConnection());}@Override public Connection getConnection(String u,String p)throws SQLException{return connection(super.getConnection(u,p));}};return bean;}};}
    }
    private static Connection connection(Connection source){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
        if(method.getName().equals("getMetaData"))METADATA.incrementAndGet();Object result=invoke(source,method,args);
        if(result instanceof PreparedStatement statement)return statement(statement,(String)args[0],PreparedStatement.class);
        if(result instanceof Statement statement)return statement(statement,null,Statement.class);return result;
    });}
    private static Object statement(Statement source,String sql,Class<?> type){return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{if(method.getName().startsWith("execute")){String query=sql!=null?sql:args!=null&&args.length>0&&args[0]instanceof String s?s:"";if(query.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT"))SELECTS.incrementAndGet();}return invoke(source,method,args);});}
    private static Object invoke(Object target,Method method,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
}
