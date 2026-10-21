package com.library.history;

import com.library.common.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static com.library.history.HistoryContextContracts.*;

/** B60/B61: evidence from existing IDs and exact bytes only; this component issues SELECTs only. */
@Service
@Transactional(readOnly = true)
public class HistoryContextService {
    private final JdbcTemplate jdbc;
    private final boolean mysql;

    public HistoryContextService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            mysql = "MySQL".equals(connection.getMetaData().getDatabaseProductName());
        } catch (SQLException error) {
            throw new IllegalStateException("无法识别历史资料数据库", error);
        }
    }

    public Context context(String kind, long id) {
        Subject subject = subject(kind, id);
        Scope scope = scope(subject);
        List<ArchiveType> types = jdbc.query("SELECT r.record_type,COUNT(*) AS total FROM legacy_record r WHERE "
                        + scope.where + " GROUP BY r.record_type," + binary("r.record_type") + " ORDER BY " + binary("r.record_type"),
                (r, i) -> new ArchiveType(r.getString(1), r.getLong(2)), scope.args.toArray());
        return new Context(subject.kind, Long.toString(id), subject.identity(),
                new Provenance(subject.classification, subject.sourceKey, subject.sourceSystem,
                        subject.state, List.copyOf(subject.basis), List.copyOf(subject.warnings)),
                new Links(str(subject.memberId), str(subject.bookId), str(subject.copyId),
                        str(subject.orderId), str(subject.memberArchiveId)), types, true);
    }

    public PageResult<Archive> records(String kind, long id, int page, int size, String type) {
        if (!Set.of("ALL", "MEMBER", "ORDER", "BOOK", "PLAN").contains(type))
            throw new HistoryContextException(400, "资料类型不正确");
        Subject subject = subject(kind, id);
        Scope scope = scope(subject);
        String where = scope.where;
        List<Object> args = new ArrayList<>(scope.args);
        if (!"ALL".equals(type)) {
            where += " AND " + binary("r.record_type") + "=?";
            args.add(bytes(type));
        }
        long safePage = Math.max(1, page), safeSize = Math.min(100, Math.max(1, size));
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM legacy_record r WHERE " + where,
                Long.class, args.toArray());
        args.add(safeSize);
        args.add((safePage - 1) * safeSize);
        // Raw JSON is only loaded by the scoped detail endpoint, never by a list or count.
        List<Archive> rows = jdbc.query(projection(false) + " FROM legacy_record r WHERE " + where
                        + " ORDER BY r.source_time DESC,r.id DESC LIMIT ? OFFSET ?",
                (r, i) -> archive(r, subject), args.toArray());
        return new PageResult<>(rows, total, safePage, safeSize);
    }

    public ArchiveDetail record(String kind, long id, long recordId) {
        Subject subject = subject(kind, id);
        Scope scope = scope(subject);
        List<Object> args = new ArrayList<>(scope.args);
        args.add(recordId);
        var rows = jdbc.query(projection(true) + " FROM legacy_record r WHERE " + scope.where + " AND r.id=?",
                (r, i) -> {
                    Archive a = archive(r, subject);
                    return new ArchiveDetail(a.id(), a.sourceSystem(), a.recordType(), a.sourceKey(),
                            a.displayName(), a.summary(), a.userId(), a.bookId(), a.sourceTime(),
                            a.association(), r.getString("details_json"));
                }, args.toArray());
        if (rows.isEmpty()) throw new HistoryContextException(404, "当前资料未关联该原系统档案");
        return rows.get(0);
    }

    private Subject subject(String kind, long id) {
        if (id < 1) throw new HistoryContextException(404, "当前业务资料不存在或已删除");
        return switch (kind) {
            case "members" -> member(id);
            case "books" -> book(id);
            case "borrows" -> borrow(id);
            default -> throw new HistoryContextException(400, "业务资料类型不正确");
        };
    }

    private Subject member(long id) {
        var rows = jdbc.query("SELECT u.id,u.username,u.real_name,p.card_no,p.source_key,p.source_system,p.source_archive_id "
                        + "FROM sys_user u LEFT JOIN member_profile p ON p.user_id=u.id "
                        + "WHERE u.id=? AND u.deleted=0 AND u.role='READER'", (r, i) -> {
                    Subject s = new Subject("MEMBER", id);
                    s.memberId = id;
                    memberIdentity(s, r);
                    s.sourceKey = r.getString("source_key");
                    s.sourceSystem = r.getString("source_system");
                    s.profileArchiveId = number(r, "source_archive_id");
                    return s;
                }, id);
        Subject s = required(rows);
        s.memberValid = true;
        resolveMember(s);
        return s;
    }

    private void resolveMember(Subject s) {
        var counts = jdbc.queryForMap("SELECT COUNT(*) AS total,COALESCE(SUM(CASE WHEN " + exact("r.record_type", "'MEMBER'")
                + " THEN 1 ELSE 0 END),0) AS member_total FROM legacy_record r WHERE r.user_id=?", s.memberId);
        long memberArchiveCount = ((Number) counts.get("member_total")).longValue();
        long allArchiveCount = ((Number) counts.get("total")).longValue();
        s.basis.add("会员身份使用 sys_user.id 与 member_profile.user_id；不按姓名、手机号或卡号合并。");
        if (s.sourceKey == null && s.profileArchiveId == null && allArchiveCount == 0) {
            s.classification = "SYSTEM";
            return;
        }
        if ("manual".equals(s.sourceSystem) && s.sourceKey != null && s.sourceKey.startsWith("manual:")
                && s.sourceKey.length() > 7 && s.profileArchiveId == null && allArchiveCount == 0) {
            s.classification = "SYSTEM";
            s.state = "VERIFIED";
            s.basis.add("member_profile.source_system=manual，保留手工建档来源键。");
            return;
        }
        s.classification = "UNVERIFIED";
        s.state = "UNVERIFIED";
        if (s.sourceKey != null && s.sourceKey.startsWith("huiben:") && s.sourceKey.length() > 7
                && (s.sourceSystem == null || "huiben".equals(s.sourceSystem))) {
            String uid = s.sourceKey.substring(7);
            var rows = jdbc.query("SELECT id,user_id,book_id FROM legacy_record r WHERE "
                            + exact("r.source_system", "'huiben'") + " AND " + exact("r.record_type", "'MEMBER'")
                            + " AND " + binary("r.source_key") + "=? ORDER BY r.id LIMIT 2",
                    (r, i) -> new MemberArchive(r.getLong("id"), number(r, "user_id"), number(r, "book_id")), bytes(uid));
            if (rows.size() == 1 && Objects.equals(rows.get(0).memberId, s.memberId)
                    && rows.get(0).bookId == null
                    && (s.profileArchiveId == null || s.profileArchiveId == rows.get(0).id)
                    && memberArchiveCount == 1) {
                s.classification = "IMPORTED";
                s.state = "VERIFIED";
                s.sourceSystem = "huiben";
                s.memberArchiveId = rows.get(0).id;
                s.basis.add("member_profile.source_key 与原 MEMBER 来源键精确对应，且档案 user_id 为当前会员；显式 source_archive_id 如存在也须一致。");
                return;
            }
        }
        s.warnings.add("会员来源身份或原档案关联不完整、冲突，保留原来源键并列为待核实。");
    }

    private Subject book(long id) {
        var rows = jdbc.query("SELECT id,title,isbn FROM book WHERE id=? AND deleted=0", (r, i) -> {
            Subject s = new Subject("BOOK", id);
            s.bookId = id;
            s.bookTitle = r.getString("title");
            s.isbn = r.getString("isbn");
            return s;
        }, id);
        Subject s = required(rows);
        s.bookValid = true;
        var counts = jdbc.queryForMap("SELECT COUNT(*) AS total,COALESCE(SUM(CASE WHEN " + validBookArchive()
                + " THEN 0 ELSE 1 END),0) AS invalid FROM legacy_record r WHERE r.book_id=? AND "
                + exact("r.record_type", "'BOOK'"), id);
        long total = ((Number) counts.get("total")).longValue();
        long invalid = ((Number) counts.get("invalid")).longValue();
        s.basis.add("图书原档案仅使用 legacy_record.book_id 对应当前 book.id；ISBN与列表序号不作为身份关联。");
        if (total == 0) {
            s.classification = "SYSTEM";
        } else if (invalid == 0) {
            s.classification = "IMPORTED";
            s.sourceSystem = "huiben";
            s.state = "VERIFIED";
            s.basis.add("原 BOOK 来源键与当前书目的已存实体册条码按完整字节对应。");
        } else {
            s.classification = "UNVERIFIED";
            s.state = "UNVERIFIED";
            s.warnings.add("存在已存 book_id 原档案，但来源系统、条码或册归属尚待核实。");
        }
        return s;
    }

    private Subject borrow(long id) {
        var rows = jdbc.query("SELECT r.id,r.user_id,r.book_id,r.copy_id,r.source_key,r.status,r.borrow_date,r.due_date,r.return_date,r.due_date_kind,"
                        + "u.id AS existing_member,u.deleted AS member_deleted,u.role,u.username,u.real_name,p.card_no,"
                        + "b.id AS existing_book,b.deleted AS book_deleted,b.title,b.isbn,c.id AS existing_copy,c.book_id AS copy_book_id,c.barcode "
                        + "FROM borrow_record r LEFT JOIN sys_user u ON u.id=r.user_id LEFT JOIN member_profile p ON p.user_id=u.id "
                        + "LEFT JOIN book b ON b.id=r.book_id LEFT JOIN book_copy c ON c.id=r.copy_id WHERE r.id=? AND r.deleted=0",
                (r, i) -> {
                    Subject s = new Subject("BORROW", id);
                    s.memberId = number(r, "user_id");
                    s.bookId = number(r, "book_id");
                    s.copyId = number(r, "copy_id");
                    s.sourceKey = r.getString("source_key");
                    s.status = r.getString("status");
                    s.borrowDate = r.getString("borrow_date");
                    s.dueDate = r.getString("due_date");
                    s.returnDate = r.getString("return_date");
                    s.dueDateKind = r.getString("due_date_kind");
                    s.memberValid = number(r, "existing_member") != null && r.getInt("member_deleted") == 0 && "READER".equals(r.getString("role"));
                    s.bookValid = number(r, "existing_book") != null && r.getInt("book_deleted") == 0;
                    s.copyValid = s.copyId != null && Objects.equals(number(r, "existing_copy"), s.copyId)
                            && s.bookValid && Objects.equals(number(r, "copy_book_id"), s.bookId);
                    if (s.memberValid) memberIdentity(s, r);
                    if (s.bookValid) { s.bookTitle = r.getString("title"); s.isbn = r.getString("isbn"); }
                    if (s.copyValid) s.barcode = r.getString("barcode");
                    return s;
                }, id);
        Subject s = required(rows);
        if (!s.memberValid) s.warnings.add("借阅所存会员身份缺失、已删除或角色不符；不展示该会员的原档案。");
        if (!s.bookValid) s.warnings.add("借阅所存书目身份缺失或已删除；不展示书目原档案。");
        if (s.copyId != null && !s.copyValid) s.warnings.add("借阅所存实体册不存在或不属于所存书目；不展示其条码和原 BOOK 档案。");
        s.basis.add("当前借阅身份使用 borrow_record.id、user_id、book_id、copy_id，分别核对现存实体。");
        Subject memberEvidence = s.memberValid ? member(s.memberId) : null;
        if (memberEvidence != null) s.memberArchiveId = memberEvidence.memberArchiveId;
        var orders = jdbc.query("SELECT i.order_id,i.book_id,i.copy_id,i.barcode,o.id AS existing_order,o.user_id "
                        + "FROM circulation_order_item i LEFT JOIN circulation_order o ON o.id=i.order_id WHERE i.record_id=? ORDER BY i.order_id LIMIT 2",
                (r, i) -> new CounterOrder(r.getLong("order_id"), number(r, "existing_order"), number(r, "user_id"),
                        number(r, "book_id"), number(r, "copy_id"), r.getString("barcode")), id);
        if (!orders.isEmpty()) {
            CounterOrder o = orders.get(0);
            if (orders.size() == 1 && Objects.equals(o.existingId, o.id) && s.memberValid && s.bookValid && s.copyValid
                    && Objects.equals(o.memberId, s.memberId) && Objects.equals(o.bookId, s.bookId)
                    && Objects.equals(o.copyId, s.copyId) && Objects.equals(o.barcode, s.barcode) && s.sourceKey == null) {
                s.orderId = o.id;
                s.classification = "COUNTER_ORDER";
                s.state = "VERIFIED";
                s.basis.add("circulation_order_item.record_id 精确等于当前借阅ID，订单会员、书目、册ID及条码与当前记录一致。");
            } else {
                unverified(s, "柜台明细存在但订单、会员、书目、实体册或来源键冲突，不能认作已核实柜台订单。");
            }
        } else if (s.sourceKey == null && s.memberValid && s.bookValid && (s.copyId == null || s.copyValid)) {
            s.classification = "SYSTEM";
            s.basis.add("本系统借阅无迁入来源键，也没有已存柜台订单明细关联。");
        } else if (s.sourceKey != null && s.sourceKey.matches("huiben:[^:]+:[1-9][0-9]*")
                && s.memberValid && s.bookValid && s.copyValid) {
            String memberKey = s.sourceKey.substring(0, s.sourceKey.lastIndexOf(':'));
            long bookSources = count("r.book_id=? AND " + exact("r.record_type", "'BOOK'") + " AND "
                            + exact("r.source_system", "'huiben'") + " AND " + binary("r.source_key") + "=?",
                    s.bookId, bytes(s.barcode));
            if ("IMPORTED".equals(memberEvidence.classification) && Objects.equals(memberKey, memberEvidence.sourceKey) && bookSources == 1) {
                s.classification = "IMPORTED";
                s.sourceSystem = "huiben";
                s.state = "VERIFIED";
                s.basis.add("迁入明细键的会员部分与已核实 MEMBER 档案一致，所存册与当前书目及原 BOOK 条码一致。");
            } else unverified(s, "迁入借阅键未同时对应已核实的会员与实体册原档案。");
        } else unverified(s, "借阅来源或当前会员、书目、实体册身份待核实。");
        if (s.sourceKey != null) s.warnings.add("来源键保留原文；huiben:会员来源键:序号 是迁入明细键，不是原订单号。");
        s.warnings.add("同会员 ORDER 档案仅供会员历史资料参考，与本条借阅没有确切旧订单关联；不按姓名、手机号、日期或ISBN猜配。");
        return s;
    }

    private Scope scope(Subject s) {
        if ("MEMBER".equals(s.kind)) return new Scope("r.user_id=?", List.of(s.memberId));
        if ("BOOK".equals(s.kind)) return new Scope("r.book_id=? AND " + exact("r.record_type", "'BOOK'"), List.of(s.bookId));
        List<String> parts = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (s.memberValid) {
            parts.add("(r.user_id=? AND (" + exact("r.record_type", "'MEMBER'") + " OR " + exact("r.record_type", "'ORDER'") + "))");
            args.add(s.memberId);
        }
        if (s.copyValid) {
            parts.add("(r.book_id=? AND " + exact("r.record_type", "'BOOK'") + " AND "
                    + exact("r.source_system", "'huiben'") + " AND " + binary("r.source_key") + "=? AND (r.user_id IS NULL OR r.user_id=?))");
            args.add(s.bookId); args.add(bytes(s.barcode)); args.add(s.memberId);
        }
        return new Scope(parts.isEmpty() ? "1=0" : "(" + String.join(" OR ", parts) + ")", args);
    }

    private String projection(boolean details) {
        return "SELECT r.id,r.source_system,r.record_type,r.source_key,r.display_name,r.summary,r.user_id,r.book_id,r.source_time,"
                + "CASE WHEN " + validBookArchive() + " THEN 1 ELSE 0 END AS valid_book" + (details ? ",r.details_json" : "");
    }

    private String validBookArchive() {
        return exact("r.source_system", "'huiben'") + " AND EXISTS(SELECT 1 FROM book_copy bc JOIN book b ON b.id=bc.book_id "
                + "WHERE bc.book_id=r.book_id AND b.deleted=0 AND bc.barcode_key=" + binary("r.source_key") + ")";
    }

    private Archive archive(ResultSet r, Subject s) throws SQLException {
        String type = r.getString("record_type");
        String scope = "BOOK".equals(type) && !"MEMBER".equals(s.kind)
                ? ("BORROW".equals(s.kind) ? "COPY_CONTEXT" : "BOOK_CONTEXT") : "MEMBER_CONTEXT";
        String basis;
        String state = "VERIFIED";
        if ("BOOK".equals(type)) {
            state = r.getInt("valid_book") == 1 ? "VERIFIED" : "UNVERIFIED";
            basis = "COPY_CONTEXT".equals(scope)
                    ? "借阅 copy_id 指向所存 book_id 的实体册，原 BOOK 的 book_id 一致且 source_key 与该册条码完整字节相同；这不是旧订单关联。"
                    : "原档案保存的 book_id 对应书目；来源条码和实体册归属另行核对。";
        } else if ("ORDER".equals(type)) {
            basis = "原 ORDER 档案 user_id 对应当前会员，仅为同会员资料，不代表本条借阅的旧订单；原状态及内容只读保留。";
        } else if ("MEMBER".equals(type)) {
            state = Objects.equals(s.memberArchiveId, number(r, "id")) ? "VERIFIED" : "UNVERIFIED";
            basis = "原 MEMBER 档案 user_id 对应当前会员；其来源键及显式档案ID须同时与会员来源一致，不能据姓名或手机号认定身份。";
        } else {
            basis = "原档案保存的 user_id 对应当前会员，仅为会员资料关联。";
        }
        return new Archive(r.getString("id"), r.getString("source_system"), type, r.getString("source_key"),
                r.getString("display_name"), r.getString("summary"), str(number(r, "user_id")), str(number(r, "book_id")),
                r.getString("source_time"), new Association(scope, basis, state));
    }

    private long count(String where, Object... args) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM legacy_record r WHERE " + where, Long.class, args);
        return value == null ? 0 : value;
    }
    private String binary(String expression) { return "CAST(" + expression + " AS " + (mysql ? "BINARY" : "VARBINARY") + ")"; }
    private String exact(String left, String right) { return binary(left) + "=" + binary(right); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static Long number(ResultSet r, String field) throws SQLException { return r.getObject(field) == null ? null : r.getLong(field); }
    private static String str(Long value) { return value == null ? null : value.toString(); }
    private static Subject required(List<Subject> rows) {
        if (rows.isEmpty()) throw new HistoryContextException(404, "当前业务资料不存在或已删除");
        return rows.get(0);
    }
    private static void memberIdentity(Subject s, ResultSet r) throws SQLException {
        s.username = r.getString("username"); s.memberName = r.getString("real_name"); s.cardNo = r.getString("card_no");
    }
    private static void unverified(Subject s, String warning) {
        s.classification = "UNVERIFIED"; s.state = "UNVERIFIED"; s.warnings.add(warning);
    }
    private record Scope(String where, List<Object> args) {}
    private record MemberArchive(long id, Long memberId, Long bookId) {}
    private record CounterOrder(long id, Long existingId, Long memberId, Long bookId, Long copyId, String barcode) {}

    private static final class Subject {
        final String kind;
        final long id;
        Long memberId, bookId, copyId, orderId, memberArchiveId, profileArchiveId;
        String username, memberName, cardNo, bookTitle, isbn, barcode, status, borrowDate, dueDate, returnDate, dueDateKind;
        String sourceKey, sourceSystem, classification = "UNVERIFIED", state = "NONE";
        boolean memberValid, bookValid, copyValid;
        final List<String> basis = new ArrayList<>(), warnings = new ArrayList<>();
        Subject(String kind, long id) { this.kind = kind; this.id = id; }
        Identity identity() {
            return new Identity(str(memberId), username, memberName, cardNo, str(bookId), bookTitle, isbn,
                    "BORROW".equals(kind) ? Long.toString(id) : null, str(copyId), barcode, status,
                    borrowDate, dueDate, returnDate, dueDateKind);
        }
    }
}
