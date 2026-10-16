package com.library.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Shared lifecycle interpretation. Raw legacy values never acquire evidence from their label alone. */
@Service
@RequiredArgsConstructor
public class CopyInventoryProjection {
    private final JdbcTemplate jdbc;
    private static final Map<javax.sql.DataSource,Boolean> DIALECTS=Collections.synchronizedMap(new WeakHashMap<>());
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record V2(String schema,long registeredCopies,long registeredAvailableCopies,long registeredBorrowedCopies,
            long registeredInStockCopies,long registeredDisabledCopies,long registeredWrittenOffCopies,long registeredUnknownCopies,
            long inStockDifference,long availableDifference,long borrowedDifference,long unexplainedStockBalanceDifference,
            boolean hasHistoricalDifference,boolean hasUnknownStatus,boolean hasLoanLinkAnomaly,boolean hasKnownUnavailable) {}
    public boolean h2(){return DIALECTS.computeIfAbsent(Objects.requireNonNull(jdbc.getDataSource()),key->Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c -> "H2".equals(c.getMetaData().getDatabaseProductName()))));}
    public String binary(String expression){return h2()?"STRINGTOUTF8("+expression+")":"CAST("+expression+" AS BINARY)";}
    public String exact(String expression,String value){return binary(expression)+"="+binary("'"+value+"'");}
    public String classificationSql(String c) {
        String disabled = evidenceSql(c,"DISABLED","a.action_type IN ('DISABLE','RESTORE')"), written=evidenceSql(c,"WRITTEN_OFF","a.action_type='WRITE_OFF'");
        return "CASE WHEN "+exact(c+".status","AVAILABLE")+" THEN 'AVAILABLE' WHEN "+exact(c+".status","BORROWED")+" THEN 'BORROWED' WHEN "+disabled+" THEN 'DISABLED' WHEN "+written+" THEN 'WRITTEN_OFF' ELSE 'UNKNOWN' END";
    }
    private String evidenceSql(String c,String state,String kind) {
        return "("+exact(c+".status",state)+" AND EXISTS(SELECT 1 FROM book_copy_action a JOIN book_copy_action_request ar ON ar.id=a.request_id WHERE a.id="+c+".last_state_action_id AND a.copy_id="+c+".id AND a.book_id="+c+".book_id AND "+binary("a.after_status")+"="+binary(c+".status")+" AND a.after_version<="+c+".action_version AND ar.state='COMMITTED' AND "+kind+"))";
    }
    public String classification(Long copyId) {return jdbc.queryForObject("SELECT "+classificationSql("c")+" FROM book_copy c WHERE c.id=?",String.class,copyId);}
    public V2 forBook(Long id){V2 value=forBooks(List.of(id)).get(id);if(value==null)throw new IllegalArgumentException("书目不存在");return value;}
    public Map<Long,V2> forBooks(Collection<Long> ids){if(ids.isEmpty())return Map.of();return project("b.id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",ids.toArray());}
    /** The whole active catalog uses three data statements regardless of the number of titles. */
    public Map<Long,V2> forActiveBooks(){return project("b.deleted=0 AND b.status=1",new Object[0]);}
    private static final class Counts {
        long total,available,a,b,d,w,u,loans;boolean anomaly;Map<Long,String> states=new HashMap<>();Map<Long,Integer> linked=new HashMap<>();
        Counts(long total,long available){this.total=total;this.available=available;}
        void loan(Map<String,Object> row){long own=((Number)row.get("book_id")).longValue();Object copy=row.get("copy_id"),book=row.get("copy_book");if(row.get("member_id")==null)anomaly=true;if(copy!=null){linked.merge(((Number)copy).longValue(),1,Integer::sum);if(book==null||((Number)book).longValue()!=own)anomaly=true;}}
        V2 finish(){for(var state:states.entrySet()){int n=linked.getOrDefault(state.getKey(),0);if(state.getValue().equals("BORROWED")?n!=1:n!=0)anomaly=true;}return calculate(total,available,loans,states.size(),a,b,d,w,u,anomaly);}
    }
    private Map<Long,V2> project(String filter,Object[] parameters){
        Map<Long,Counts> counts=new LinkedHashMap<>();
        jdbc.query("SELECT b.id,b.stock_total,b.stock_available FROM book b WHERE "+filter+" ORDER BY b.id",rs->{counts.put(rs.getLong(1),new Counts(rs.getLong(2),rs.getLong(3)));},parameters);
        jdbc.query("SELECT c.id,c.book_id,"+classificationSql("c")+" AS classification FROM book_copy c JOIN book b ON b.id=c.book_id WHERE "+filter+" ORDER BY c.id",rs->{Counts c=counts.get(rs.getLong(2));String state=rs.getString(3);c.states.put(rs.getLong(1),state);switch(state){case "AVAILABLE"->c.a++;case "BORROWED"->c.b++;case "DISABLED"->c.d++;case "WRITTEN_OFF"->c.w++;default->c.u++;}},parameters);
        List<Object> loanParameters=new ArrayList<>(Arrays.asList(parameters));loanParameters.addAll(Arrays.asList(parameters));
        List<Map<String,Object>> loans=jdbc.queryForList("SELECT r.copy_id,r.book_id,c.book_id AS copy_book,u.id AS member_id FROM borrow_record r LEFT JOIN book_copy c ON c.id=r.copy_id LEFT JOIN sys_user u ON u.id=r.user_id AND u.deleted=0 WHERE r.deleted=0 AND r.status IN ('BORROWING','OVERDUE') AND (r.book_id IN(SELECT b.id FROM book b WHERE "+filter+") OR c.book_id IN(SELECT b.id FROM book b WHERE "+filter+"))",loanParameters.toArray());
        for(var row:loans){long book=((Number)row.get("book_id")).longValue();Counts own=counts.get(book);if(own!=null){own.loans++;own.loan(row);}Object copyBook=row.get("copy_book");if(copyBook!=null&&((Number)copyBook).longValue()!=book){Counts actual=counts.get(((Number)copyBook).longValue());if(actual!=null)actual.loan(row);}}
        Map<Long,V2> result=new LinkedHashMap<>();counts.forEach((id,count)->result.put(id,count.finish()));return result;
    }
    public static V2 calculate(long total,long available,long loans,long r,long a,long b,long d,long w,long u,boolean anomaly){
        long h=r-w,td=total-h,ad=available-a,bd=loans-b,unexplained=total-available-loans-d;
        return new V2("copy-lifecycle-v1",r,a,b,h,d,w,u,td,ad,bd,unexplained,td!=0||ad!=0||bd!=0||unexplained!=0,u>0,anomaly,d+w>0);
    }
    public static V2 after(V2 old,long total,long available,long deltaA,long deltaD,long deltaW){
        long loans=old.borrowedDifference()+old.registeredBorrowedCopies();return calculate(total,available,loans,old.registeredCopies(),old.registeredAvailableCopies()+deltaA,old.registeredBorrowedCopies(),old.registeredDisabledCopies()+deltaD,old.registeredWrittenOffCopies()+deltaW,old.registeredUnknownCopies(),old.hasLoanLinkAnomaly());
    }
    public static V2 sum(List<V2> rows){
        long r=0,a=0,b=0,h=0,d=0,w=0,u=0,td=0,ad=0,bd=0,un=0;boolean historical=false,anomaly=false;
        for(V2 row:rows){r+=row.registeredCopies();a+=row.registeredAvailableCopies();b+=row.registeredBorrowedCopies();h+=row.registeredInStockCopies();d+=row.registeredDisabledCopies();w+=row.registeredWrittenOffCopies();u+=row.registeredUnknownCopies();td+=row.inStockDifference();ad+=row.availableDifference();bd+=row.borrowedDifference();un+=row.unexplainedStockBalanceDifference();historical|=row.hasHistoricalDifference();anomaly|=row.hasLoanLinkAnomaly();}
        return new V2("copy-lifecycle-v1",r,a,b,h,d,w,u,td,ad,bd,un,historical,u>0,anomaly,d+w>0);
    }
}
