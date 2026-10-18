package com.library.catalogsets;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;

/** Counts actual executed SELECT statements rather than service method invocations. */
final class SelectCounterDataSource extends DelegatingDataSource {
    final List<String> sql=new ArrayList<>();
    SelectCounterDataSource(DataSource source){super(source);}
    @Override public Connection getConnection()throws SQLException{return connection(super.getConnection());}
    @Override public Connection getConnection(String user,String password)throws SQLException{return connection(super.getConnection(user,password));}
    Object call(Object target,Method m,Object[] args)throws Throwable{try{return m.invoke(target,args);}catch(InvocationTargetException e){throw e.getTargetException();}}
    Connection connection(Connection c){return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{Object r=call(c,m,a);if(r instanceof Statement s){String prepared=m.getName().equals("prepareStatement")?(String)a[0]:null;return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{s instanceof PreparedStatement?PreparedStatement.class:Statement.class},(sp,sm,sa)->{if(sm.getName().startsWith("execute")){String query=prepared!=null?prepared:sa!=null&&sa.length>0&&sa[0] instanceof String text?text:null;if(query!=null&&(query.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT")||query.stripLeading().toUpperCase(Locale.ROOT).startsWith("WITH")))sql.add(query);}return call(s,sm,sa);});}return r;});}
}
