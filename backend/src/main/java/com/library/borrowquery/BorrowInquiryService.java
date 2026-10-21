package com.library.borrowquery;

import com.library.history.HistoryBorrowOriginSql;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.library.borrowquery.BorrowInquiryContracts.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class BorrowInquiryService {
    public static final int MAX_EXPORT = 20_000;
    public static final String SCOPE = "当前筛选的借阅明细，不与原系统累计相加，不代表订单次数";
    private final JdbcTemplate jdbc;
    private final HistoryBorrowOriginSql origins;

    public BorrowInquiryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            origins = new HistoryBorrowOriginSql("MySQL".equals(connection.getMetaData().getDatabaseProductName()));
        } catch (SQLException error) { throw new IllegalStateException("无法识别借阅查询数据库", error); }
    }

    public Page page(String page, String size, String keyword, String status, String origin) {
        long safePage = positive(page, "页码"), safeSize = positive(size, "每页条数");
        if (safeSize > 100) throw new BorrowInquiryException("每页最多100条");
        Filters filters = filters(keyword, status, origin);
        Query query = query(filters);
        Summary summary = summary(query);
        String asOf = Instant.now().toString();
        long offset;
        try { offset = Math.multiplyExact(safePage - 1, safeSize); }
        catch (ArithmeticException error) { throw new BorrowInquiryException("页码超出范围"); }
        List<Object> args = new ArrayList<>(query.args);
        args.add(safeSize); args.add(offset);
        List<Row> rows = jdbc.query(projection() + query.where + " ORDER BY q.create_time DESC,q.id DESC LIMIT ? OFFSET ?",
                this::row, args.toArray());
        return new Page(rows, summary.total(), safePage, safeSize, (summary.total() + safeSize - 1) / safeSize,
                asOf, filters, summary);
    }

    public Export export(String keyword, String status, String origin) {
        Filters filters = filters(keyword, status, origin);
        Query query = query(filters);
        Summary summary = summary(query); // The first consistent read establishes the MySQL snapshot.
        if (summary.total() > MAX_EXPORT) throw new BorrowInquiryException("当前筛选超过20000条，请缩小筛选范围后导出");
        String asOf = Instant.now().toString();
        List<Row> rows = jdbc.query(projection() + query.where + " ORDER BY q.create_time DESC,q.id DESC",
                this::row, query.args.toArray());
        if (rows.size() != summary.total()) throw new IllegalStateException("借阅导出快照数量不一致");
        String filename = "borrow-inquiry-" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
                .format(Instant.parse(asOf)) + ".csv";
        return new Export(BorrowInquiryCsv.render(rows, filters, asOf, SCOPE), summary.total(), asOf, filename);
    }

    private Summary summary(Query query) {
        return jdbc.queryForObject(origins.cte() + "SELECT COUNT(*),"
                + "COALESCE(SUM(CASE WHEN q.origin='IMPORTED' THEN 1 ELSE 0 END),0),"
                + "COALESCE(SUM(CASE WHEN q.origin='COUNTER_ORDER' THEN 1 ELSE 0 END),0),"
                + "COALESCE(SUM(CASE WHEN q.origin='SYSTEM' THEN 1 ELSE 0 END),0),"
                + "COALESCE(SUM(CASE WHEN q.origin='UNVERIFIED' THEN 1 ELSE 0 END),0) FROM borrow_classified q"
                + query.where, (r, i) -> new Summary(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4), r.getLong(5), "条", SCOPE), query.args.toArray());
    }

    private Query query(Filters f) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (!f.keyword().isEmpty()) {
            where.append(" AND ((q.member_valid=1 AND q.member_name LIKE ?) OR (q.book_valid=1 AND (q.current_book_title LIKE ? OR q.current_book_isbn LIKE ?)) OR ")
                    .append(origins.binary("q.valid_barcode")).append("=?)");
            args.add("%" + f.keyword() + "%"); args.add("%" + f.keyword() + "%"); args.add("%" + f.keyword() + "%");
            args.add(f.keyword().getBytes(StandardCharsets.UTF_8));
        }
        if ("ACTIVE".equals(f.status())) where.append(" AND q.status IN ('BORROWING','OVERDUE')");
        else if (f.status() != null) { where.append(" AND q.status=?"); args.add(f.status()); }
        if (!"ALL".equals(f.origin())) { where.append(" AND q.origin=?"); args.add(f.origin()); }
        return new Query(where.toString(), args);
    }

    private String projection() {
        return origins.cte() + "SELECT q.id,q.user_id,q.book_id,q.copy_id,"
                + "CASE WHEN q.member_valid=1 THEN q.member_name ELSE NULL END AS safe_member_name,"
                + "CASE WHEN q.book_valid=1 THEN q.current_book_title ELSE NULL END AS safe_book_title,"
                + "CASE WHEN q.book_valid=1 THEN q.current_book_isbn ELSE NULL END AS safe_book_isbn,"
                + "CASE WHEN q.book_valid=1 THEN q.current_book_cover ELSE NULL END AS safe_book_cover,"
                + "q.valid_barcode,q.borrow_date,q.due_date,q.return_date,q.due_date_kind,q.renew_count,q.status,q.remark,"
                + "COALESCE((SELECT SUM(f.amount) FROM fine_record f WHERE f.borrow_id=q.id AND f.deleted=0),0) AS fine_amount,"
                + "q.origin,q.counter_order_id FROM borrow_classified q";
    }
    private Row row(ResultSet r, int index) throws SQLException {
        String name = r.getString("safe_member_name");
        return new Row(safe(r.getLong("id")), safe(r.getLong("user_id")), safe(r.getLong("book_id")), nullableId(r,"copy_id"),
                name, name, r.getString("safe_book_title"), r.getString("safe_book_isbn"), r.getString("safe_book_cover"),
                r.getString("valid_barcode"), r.getString("borrow_date"), r.getString("due_date"), r.getString("return_date"),
                r.getString("due_date_kind"), r.getInt("renew_count"), r.getString("status"), r.getString("remark"),
                r.getBigDecimal("fine_amount"), r.getString("origin"), nullableId(r,"counter_order_id"));
    }
    private static Long nullableId(ResultSet r, String field) throws SQLException { return r.getObject(field) == null ? null : safe(r.getLong(field)); }
    private static long safe(long id) { if (id < 1 || id > 9_007_199_254_740_991L) throw new BorrowInquiryException("业务编号超出安全整数范围，请联系管理员核实"); return id; }
    private static long positive(String text, String name) {
        try { if (text == null || !text.matches("[1-9][0-9]{0,9}")) throw new NumberFormatException(); return Long.parseLong(text); }
        catch (NumberFormatException error) { throw new BorrowInquiryException(name + "必须为正整数"); }
    }
    private static Filters filters(String keyword, String status, String origin) {
        String key = keyword == null ? "" : keyword, state = status == null || status.isEmpty() ? null : status, source = origin == null ? "ALL" : origin;
        if (key.length() > 200) throw new BorrowInquiryException("关键词最多200个字符");
        if (state != null && !Set.of("ACTIVE", "BORROWING", "OVERDUE", "RETURNED").contains(state)) throw new BorrowInquiryException("借阅状态不正确");
        if (!Set.of("ALL", "IMPORTED", "COUNTER_ORDER", "SYSTEM", "UNVERIFIED").contains(source)) throw new BorrowInquiryException("借阅来源不正确");
        return new Filters(key, state, source);
    }
    private record Query(String where, List<Object> args) {}
}
