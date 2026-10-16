package com.library.service;

import com.library.common.PageResult;
import com.library.dto.BookCopyQueryContracts.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

/** Management projections only. No entity save, inferred copy, location fallback or inventory correction. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class BookCopyQueryService {
    private final JdbcTemplate jdbc;
    private final CopyPlacementService placements;
    public List<com.library.entity.BookCopy> legacy(Long bookId){
        List<com.library.entity.BookCopy> rows=jdbc.query("SELECT * FROM book_copy WHERE book_id=? ORDER BY barcode",(r,n)->{var c=new com.library.entity.BookCopy();c.setId(r.getLong("id"));c.setBookId(r.getLong("book_id"));c.setBarcode(r.getString("barcode"));c.setStatus(r.getString("status"));c.setLocation(r.getString("location"));c.setActionVersion(r.getLong("action_version"));c.setLastStateActionId(r.getObject("last_state_action_id",Long.class));c.setRegisteredLocationId(r.getObject("registered_location_id",Long.class));c.setLastLocationActionId(r.getObject("last_location_action_id",Long.class));return c;},bookId);
        var map=placements.find(rows.stream().map(com.library.entity.BookCopy::getId).toList());for(var c:rows){var p=map.get(c.getId());c.setPlacement(p.placement());c.setUnassignedLocation(p.placement().kind().equals("UNASSIGNED"));c.setLocationReferenceAnomaly(p.locationReferenceAnomaly());}return rows;
    }
    private static final Set<String> STATUSES = Set.of("AVAILABLE", "BORROWED", "OTHER","DISABLED","WRITTEN_OFF","UNKNOWN");
    private static final String BOOK_COLUMNS = "b.id AS book_id,b.title,b.isbn,b.author,b.publisher,b.publish_date,b.pages,b.cover_image,b.location AS book_location,b.status AS book_status";
    private static final String COPY_COLUMNS = "c.id AS copy_id,c.barcode,c.status AS copy_status,c.location AS copy_location,c.action_version,c.last_state_action_id," + BOOK_COLUMNS;
    private static final String COPY_FROM = " FROM book_copy c JOIN book b ON b.id=c.book_id WHERE b.deleted=0";

    public PageResult<CopyRow> copies(int page, int size, String keyword, Long bookId, String status,
                                      String location, Boolean unassignedLocation, String barcode) {
        return copies(page,size,keyword,bookId,status,location,unassignedLocation,barcode,null,null);
    }
    public PageResult<CopyRow> copies(int page,int size,String keyword,Long bookId,String status,String location,Boolean unassignedLocation,String barcode,String locationKind,Long registeredLocationId) {
        int p = page(page), s = size(size);
        checkText(keyword, 200); checkText(location, 200); checkBarcode(barcode); checkId(bookId);
        if (status != null && !STATUSES.contains(status)) throw new IllegalArgumentException("实体册状态筛选无效");
        if (Boolean.TRUE.equals(unassignedLocation) && hasText(location)) throw new IllegalArgumentException("未分配位置不能同时指定位置");
        checkId(registeredLocationId);
        if(locationKind!=null&&!Set.of("TEXT","UNASSIGNED","REGISTERED").contains(locationKind))throw new IllegalArgumentException("位置类型无效");
        if(registeredLocationId!=null&&locationKind!=null&&!locationKind.equals("REGISTERED"))throw new IllegalArgumentException("位置条件冲突");
        if(Boolean.TRUE.equals(unassignedLocation)&&(registeredLocationId!=null||locationKind!=null&&!locationKind.equals("UNASSIGNED")))throw new IllegalArgumentException("位置条件冲突");
        if(hasText(location)&&(registeredLocationId!=null||locationKind!=null&&!locationKind.equals("TEXT")))throw new IllegalArgumentException("位置条件冲突");
        if (barcode != null && barcode.isEmpty()) throw new IllegalArgumentException("精确条码不能为空");
        StringBuilder where = new StringBuilder(COPY_FROM); List<Object> args = new ArrayList<>();
        addBookId(where, args, bookId);
        if (hasText(keyword)) {
            where.append(" AND (b.title LIKE ? ESCAPE '!' OR b.isbn LIKE ? ESCAPE '!' OR b.author LIKE ? ESCAPE '!' OR c.barcode LIKE ? ESCAPE '!')");
            String pattern = literalLike(keyword); for (int i = 0; i < 4; i++) args.add(pattern);
        }
        if (status != null) {
            CopyInventoryProjection projection=new CopyInventoryProjection(jdbc);
            if (status.equals("OTHER")) where.append(" AND NOT (").append(projection.exact("c.status","AVAILABLE")).append(" OR ").append(projection.exact("c.status","BORROWED")).append(")");
            else { where.append(" AND (").append(projection.classificationSql("c")).append(")=?"); args.add(status); }
        }
        String unassigned=unassignedSql();
        if(hasText(location)){where.append(" AND c.registered_location_id IS NULL AND NOT (").append(unassigned).append(") AND c.location LIKE ? ESCAPE '!'");args.add(literalLike(location));}
        if(Boolean.TRUE.equals(unassignedLocation)||"UNASSIGNED".equals(locationKind))where.append(" AND c.registered_location_id IS NULL AND (").append(unassigned).append(")");
        if("TEXT".equals(locationKind))where.append(" AND c.registered_location_id IS NULL AND NOT (").append(unassigned).append(")");
        if("REGISTERED".equals(locationKind))where.append(" AND c.registered_location_id IS NOT NULL");
        if(registeredLocationId!=null){where.append(" AND c.registered_location_id=?");args.add(registeredLocationId);}
        if (barcode != null) {
            // MySQL CAST AS BINARY preserves case, leading zeroes and trailing spaces independently of column collation.
            where.append(" AND ").append(binaryBarcode()).append("=?"); args.add(barcode.getBytes(StandardCharsets.UTF_8));
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());
        args.add(s); args.add(((long) p - 1) * s);
        List<CopyRow> rows = jdbc.query("SELECT " + copyColumns() + where + " ORDER BY b.id,c.id LIMIT ? OFFSET ?", (rs, i) -> copy(rs), args.toArray());
        return new PageResult<>(decorate(rows), total, (long) p, (long) s);
    }

    public CopyDetail detail(Long id) {
        checkId(id);
        List<CopyRow> rows = jdbc.query("SELECT " + copyColumns() + COPY_FROM + " AND c.id=?", (rs, i) -> copy(rs), id);
        if (rows.isEmpty()) return null;
        CopyRow copy = decorate(rows).get(0);
        List<CurrentLoan> loans = jdbc.query("SELECT r.id,r.status,r.borrow_date,r.due_date,r.due_date_kind,r.book_id,u.real_name,p.card_no "
                        + "FROM borrow_record r LEFT JOIN sys_user u ON u.id=r.user_id AND u.deleted=0 AND u.role='READER' "
                        + "LEFT JOIN member_profile p ON p.user_id=u.id WHERE r.copy_id=? AND r.deleted=0 AND r.status IN ('BORROWING','OVERDUE') ORDER BY r.id",
                (rs, i) -> new CurrentLoan(rs.getLong("id"), rs.getString("status"), date(rs, "borrow_date"), date(rs, "due_date"),
                        rs.getString("due_date_kind"), rs.getString("real_name"), rs.getString("card_no"), copy.bookId().equals(rs.getLong("book_id"))), id);
        boolean needsVerification = switch (copy.stateClassification()) {
            case "AVAILABLE" -> !loans.isEmpty();
            case "BORROWED" -> loans.size() != 1;
            case "DISABLED","WRITTEN_OFF" -> !loans.isEmpty();
            default -> true;
        };
        needsVerification |= copy.locationReferenceAnomaly()||loans.stream().anyMatch(loan -> !loan.bookMatches() || loan.memberName() == null);
        return new CopyDetail(copy, loans, needsVerification,new CopyInventoryProjection(jdbc).forBook(copy.bookId()));
    }

    public PageResult<CatalogSummary> catalogSummary(int page, int size, String keyword, Long bookId) {
        int p = page(page), s = size(size); checkText(keyword, 200); checkId(bookId);
        StringBuilder where = new StringBuilder(" WHERE b.deleted=0"); List<Object> args = new ArrayList<>();
        addBookId(where, args, bookId);
        if (hasText(keyword)) {
            where.append(" AND (b.title LIKE ? ESCAPE '!' OR b.isbn LIKE ? ESCAPE '!' OR b.author LIKE ? ESCAPE '!')");
            for (int i = 0; i < 3; i++) args.add(literalLike(keyword));
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM book b" + where, Long.class, args.toArray());
        args.add(s); args.add(((long) p - 1) * s);
        CopyInventoryProjection projection=new CopyInventoryProjection(jdbc);
        String query = "SELECT " + BOOK_COLUMNS + ",b.stock_total,b.stock_available,COALESCE(c.registered,0) AS registered,"
                + "COALESCE(c.available,0) AS registered_available,COALESCE(c.borrowed,0) AS registered_borrowed,COALESCE(r.borrowed,0) AS borrowed "
                + "FROM book b LEFT JOIN (SELECT book_id,COUNT(*) AS registered,SUM(CASE WHEN "+projection.exact("status","AVAILABLE")+" THEN 1 ELSE 0 END) AS available,"
                + "SUM(CASE WHEN "+projection.exact("status","BORROWED")+" THEN 1 ELSE 0 END) AS borrowed FROM book_copy GROUP BY book_id) c ON c.book_id=b.id "
                + "LEFT JOIN (SELECT book_id,COUNT(*) AS borrowed FROM borrow_record WHERE deleted=0 AND status IN ('BORROWING','OVERDUE') GROUP BY book_id) r ON r.book_id=b.id";
        List<Counts> counts = jdbc.query(query + where + " ORDER BY b.id LIMIT ? OFFSET ?", (rs, i) -> new Counts(book(rs), rs.getLong("stock_total"),
                rs.getLong("stock_available"), rs.getLong("registered"), rs.getLong("registered_available"), rs.getLong("registered_borrowed"), rs.getLong("borrowed")), args.toArray());
        Map<Long, Map<String, Long>> byStatus = new HashMap<>();
        if (!counts.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(counts.size(), "?"));
            jdbc.query("SELECT book_id,status FROM book_copy WHERE book_id IN (" + placeholders + ") ORDER BY book_id,id",
                    rs -> { byStatus.computeIfAbsent(rs.getLong("book_id"), ignored -> new LinkedHashMap<>()).merge(rs.getString("status"),1L,Long::sum); },
                    counts.stream().map(c -> c.book().id()).toArray());
        }
        var projected=projection.forBooks(counts.stream().map(c->c.book().id()).toList());
        List<CatalogSummary> rows = counts.stream().map(c -> summary(c, byStatus.getOrDefault(c.book().id(), Map.of()),projected.get(c.book().id()))).toList();
        return new PageResult<>(rows, total, (long) p, (long) s);
    }

    private record Counts(BookInfo book, long total, long available, long registered, long registeredAvailable, long registeredBorrowed, long borrowed) {}
    private CatalogSummary summary(Counts c, Map<String, Long> byStatus,CopyInventoryProjection.V2 v2) {
        long other = c.registered() - c.registeredAvailable() - c.registeredBorrowed();
        long totalDiff = c.total() - c.registered(), availableDiff = c.available() - c.registeredAvailable();
        long borrowedDiff = c.borrowed() - c.registeredBorrowed(), balanceDiff = c.total() - c.available() - c.borrowed();
        return new CatalogSummary(c.book(), c.total(), c.available(), c.registered(), c.registeredAvailable(), c.registeredBorrowed(), other,
                byStatus, c.borrowed(), totalDiff, availableDiff, borrowedDiff, balanceDiff,
                v2.hasHistoricalDifference()||v2.hasUnknownStatus()||v2.hasLoanLinkAnomaly(), true,v2);
    }
    private CopyRow copy(ResultSet rs) throws SQLException {
        String location = rs.getString("copy_location");
        return new CopyRow(rs.getLong("copy_id"), rs.getLong("book_id"), rs.getString("barcode"), rs.getString("copy_status"), location,
                false, book(rs),rs.getLong("action_version"),rs.getObject("last_state_action_id",Long.class),rs.getString("state_classification"),null,null,false);
    }
    private List<CopyRow> decorate(List<CopyRow> rows){var map=placements.find(rows.stream().map(CopyRow::id).toList());return rows.stream().map(c->{var p=map.get(c.id());return new CopyRow(c.id(),c.bookId(),c.barcode(),c.status(),c.location(),p.placement().kind().equals("UNASSIGNED"),c.book(),c.actionVersion(),c.lastStateActionId(),c.stateClassification(),p.placement(),p.lastLocationActionId(),p.locationReferenceAnomaly());}).toList();}
    private String unassignedSql(){return new CopyInventoryProjection(jdbc).h2()?"(c.location IS NULL OR REGEXP_LIKE(c.location,'^[\\x00-\\x20]*$'))":"(c.location IS NULL OR HEX(CONVERT(c.location USING binary)) REGEXP '^(0[0-9A-F]|1[0-9A-F]|20)*$')";}
    private String copyColumns(){return COPY_COLUMNS+","+new CopyInventoryProjection(jdbc).classificationSql("c")+" AS state_classification";}
    private BookInfo book(ResultSet rs) throws SQLException {
        return new BookInfo(rs.getLong("book_id"), rs.getString("title"), rs.getString("isbn"), rs.getString("author"), rs.getString("publisher"),
                date(rs, "publish_date"), rs.getObject("pages", Integer.class), rs.getString("cover_image"), rs.getString("book_location"), rs.getObject("book_status", Integer.class));
    }
    private String binaryBarcode() {
        boolean h2 = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> "H2".equals(connection.getMetaData().getDatabaseProductName())));
        return h2 ? "STRINGTOUTF8(c.barcode)" : "CAST(c.barcode AS BINARY)";
    }
    private void addBookId(StringBuilder where, List<Object> args, Long id) { if (id != null) { where.append(" AND b.id=?"); args.add(id); } }
    private int page(int value) { return Math.max(1, value); }
    private int size(int value) { return Math.max(1, Math.min(100, value)); }
    private void checkId(Long value) { if (value != null && value <= 0) throw new IllegalArgumentException("编号无效"); }
    private void checkText(String value, int max) { if (value != null && value.length() > max) throw new IllegalArgumentException("查询条件过长"); }
    private void checkBarcode(String value) {
        if (value == null) return;
        if (value.codePointCount(0, value.length()) > 64 || value.getBytes(StandardCharsets.UTF_8).length > 256)
            throw new IllegalArgumentException("精确条码过长");
        for (int i = 0; i < value.length();) {
            char c = value.charAt(i);
            if (Character.isSurrogate(c) && (!Character.isHighSurrogate(c) || i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))))
                throw new IllegalArgumentException("精确条码字符无效");
            int cp = value.codePointAt(i); if (Character.isISOControl(cp)) throw new IllegalArgumentException("精确条码字符无效"); i += Character.charCount(cp);
        }
    }
    private boolean hasText(String value) { return value != null && !value.isEmpty(); }
    private String literalLike(String value) { return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"; }
    private LocalDate date(ResultSet rs, String name) throws SQLException { return rs.getDate(name) == null ? null : rs.getDate(name).toLocalDate(); }
}
