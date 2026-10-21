package com.library.managementreport;

import com.library.borrowquery.BorrowInquiryContracts.Summary;
import com.library.borrowquery.BorrowInquiryService;
import com.library.history.HistoryBorrowOriginSql;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import static com.library.managementreport.ManagementReportContracts.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ManagementReportService {
    public static final String SCOPE = "全部当前筛选的未删除借阅明细；不受启用书目库存范围限制，不与原系统累计相加；"
            + "借阅明细单位为条，借出册次按每条明细计一次、不是不同实体册数；未还为BORROWING/OVERDUE，已还为RETURNED，逾期仅按OVERDUE状态；"
            + "明确柜台订单按筛选明细的确切订单ID去重，未知旧订单不猜配；趋势为近7个上海自然日，空借出日期计入无日期筛选总量但不归入每日趋势；排行按书目ID统计，不使用原累计";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final HistoryBorrowOriginSql origins;
    private final Clock clock;

    @Autowired
    public ManagementReportService(JdbcTemplate jdbc) { this(jdbc, Clock.systemUTC()); }
    ManagementReportService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc; this.clock = clock;
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            origins = new HistoryBorrowOriginSql("MySQL".equals(connection.getMetaData().getDatabaseProductName()));
        } catch (SQLException error) { throw new IllegalStateException("无法识别借阅报告数据库", error); }
    }

    public Report report(String keyword, String status, String origin, String startDate, String endDate) {
        Filters filters = filters(keyword, status, origin, startDate, endDate);
        Query query = query(filters);
        String from = " FROM borrow_classified q" + query.where;
        long[] counts = jdbc.queryForObject(origins.cte() + "SELECT COUNT(*),"
                + "COALESCE(SUM(CASE WHEN q.status IN ('BORROWING','OVERDUE') THEN 1 ELSE 0 END),0),"
                + "COALESCE(SUM(CASE WHEN q.status='RETURNED' THEN 1 ELSE 0 END),0),"
                + "COALESCE(SUM(CASE WHEN q.status='OVERDUE' THEN 1 ELSE 0 END),0),COUNT(DISTINCT q.counter_order_id),"
                + "COALESCE(SUM(CASE WHEN q.origin='IMPORTED' THEN 1 ELSE 0 END),0),"
                + "COALESCE(SUM(CASE WHEN q.origin='COUNTER_ORDER' THEN 1 ELSE 0 END),0),"
                + "COALESCE(SUM(CASE WHEN q.origin='SYSTEM' THEN 1 ELSE 0 END),0),"
                + "COALESCE(SUM(CASE WHEN q.origin='UNVERIFIED' THEN 1 ELSE 0 END),0)" + from,
                (r, i) -> new long[]{r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getLong(5),
                        r.getLong(6),r.getLong(7),r.getLong(8),r.getLong(9)}, query.args.toArray());
        // The first aggregate establishes the consistent database snapshot.
        Instant asOf = clock.instant(); LocalDate today = LocalDate.ofInstant(asOf, SHANGHAI);
        Totals totals = new Totals(counts[0],counts[0],counts[1],counts[2],counts[3],counts[4]);
        Summary summary = new Summary(counts[0],counts[5],counts[6],counts[7],counts[8],"条",BorrowInquiryService.SCOPE);
        List<Object> daysArgs = new ArrayList<>(query.args);
        daysArgs.add(java.sql.Date.valueOf(today.minusDays(6))); daysArgs.add(java.sql.Date.valueOf(today));
        Map<LocalDate,long[]> daily = new HashMap<>();
        jdbc.query(origins.cte() + "SELECT q.borrow_date,COUNT(*),COUNT(DISTINCT q.counter_order_id)" + from
                        + " AND q.borrow_date>=? AND q.borrow_date<=? GROUP BY q.borrow_date ORDER BY q.borrow_date",
                r -> { daily.put(r.getDate(1).toLocalDate(),new long[]{r.getLong(2),r.getLong(3)}); }, daysArgs.toArray());
        List<Trend> trend = new ArrayList<>();
        for (int n=6;n>=0;n--) {
            LocalDate day=today.minusDays(n); long[] value=daily.getOrDefault(day,new long[]{0,0});
            trend.add(new Trend(day.toString(),day.getMonthValue()+"/"+day.getDayOfMonth(),value[0],value[1]));
        }
        List<HotBook> hot = jdbc.query(origins.cte() + "SELECT q.book_id,"
                        + "CASE WHEN MAX(q.book_valid)=1 THEN MAX(q.current_book_title) ELSE NULL END,"
                        + "COUNT(*) AS detail_count,COUNT(DISTINCT q.counter_order_id)" + from
                        + " GROUP BY q.book_id ORDER BY detail_count DESC,q.book_id ASC LIMIT 10",
                (r,i) -> {
                    long id=r.getLong(1);
                    if(id<1 || id>9_007_199_254_740_991L) throw new ManagementReportException("书目业务编号超出安全范围，请核实");
                    String title=r.getString(2);
                    return new HotBook(id,title==null?"书目待核实":title,r.getLong(3),r.getLong(4));
                }, query.args.toArray());
        return new Report(asOf.toString(),today.toString(),filters,totals,summary,List.copyOf(trend),hot,SCOPE);
    }

    public Export export(String keyword, String status, String origin, String startDate, String endDate) {
        Report value=report(keyword,status,origin,startDate,endDate);
        String name="management-borrows-"+DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC).format(Instant.parse(value.asOf()))+".csv";
        return new Export(ManagementReportCsv.render(value),value.totals().borrowDetails(),value.asOf(),name);
    }

    private Query query(Filters f) {
        List<Object> args=new ArrayList<>(); StringBuilder where=new StringBuilder(" WHERE 1=1");
        if(!f.keyword().isEmpty()) {
            where.append(" AND ((q.member_valid=1 AND q.member_name LIKE ?) OR (q.book_valid=1 AND (q.current_book_title LIKE ? OR q.current_book_isbn LIKE ?)) OR ")
                    .append(origins.binary("q.valid_barcode")).append("=?)");
            args.add("%"+f.keyword()+"%");args.add("%"+f.keyword()+"%");args.add("%"+f.keyword()+"%");
            args.add(f.keyword().getBytes(StandardCharsets.UTF_8));
        }
        if("ACTIVE".equals(f.status())) where.append(" AND q.status IN ('BORROWING','OVERDUE')");
        else if(f.status()!=null) {where.append(" AND q.status=?");args.add(f.status());}
        if(!"ALL".equals(f.origin())) {where.append(" AND q.origin=?");args.add(f.origin());}
        if(f.startDate()!=null) {where.append(" AND q.borrow_date>=?");args.add(java.sql.Date.valueOf(f.startDate()));}
        if(f.endDate()!=null) {where.append(" AND q.borrow_date<=?");args.add(java.sql.Date.valueOf(f.endDate()));}
        return new Query(where.toString(),args);
    }
    private static Filters filters(String keyword,String status,String origin,String start,String end) {
        String key=keyword==null?"":keyword,state=status==null||status.isEmpty()?null:status,source=origin==null?"ALL":origin;
        if(key.length()>200) throw new ManagementReportException("关键词最多200个字符");
        if(state!=null&&!Set.of("ACTIVE","BORROWING","OVERDUE","RETURNED").contains(state)) throw new ManagementReportException("借阅状态不正确");
        if(!Set.of("ALL","IMPORTED","COUNTER_ORDER","SYSTEM","UNVERIFIED").contains(source)) throw new ManagementReportException("借阅来源不正确");
        LocalDate from=date(start),to=date(end);
        if(from!=null&&to!=null&&from.isAfter(to)) throw new ManagementReportException("开始日期不能晚于结束日期");
        return new Filters(key,state,source,from==null?null:from.toString(),to==null?null:to.toString());
    }
    private static LocalDate date(String value) {
        if(value==null||value.isEmpty()) return null;
        try {
            if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeParseException("invalid",value,0);
            LocalDate parsed=LocalDate.parse(value);
            if(parsed.getYear()<1) throw new DateTimeParseException("invalid",value,0);
            return parsed;
        } catch(DateTimeParseException error) {throw new ManagementReportException("借出日期必须为合法YYYY-MM-DD日期");}
    }
    private record Query(String where,List<Object> args) {}
}
