package com.library.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.common.PageResult;
import com.library.dto.*;
import com.library.entity.User;
import com.library.mapper.UserMapper;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MemberService {
    private final JdbcTemplate jdbc;
    private final UserMapper users;
    private final UserService userService;
    private final ObjectMapper json;
    private final Validator validator;
    private final MemberAuditWriter auditWriter;
    private final MemberEntitlementService entitlements;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String FROM = " FROM sys_user u LEFT JOIN member_profile p ON p.user_id=u.id "
            + "LEFT JOIN legacy_record l ON l.user_id=u.id AND l.source_system='huiben' AND l.record_type='MEMBER' "
            + "AND CONCAT('huiben:',l.source_key)=p.source_key ";
    private static final String COLUMNS = "SELECT u.id,u.username,u.real_name,u.phone,u.email,u.status AS account_status,u.member_revision AS account_member_revision,"
            + "p.*,l.id AS archive_id,l.source_system AS archive_system";
    private static final String READER = "u.deleted=0 AND u.role='READER'";

    public PageResult<MemberSummary> list(int page, int size, String keyword, Integer accountStatus, String serviceStatus) {
        int safePage = Math.max(1, page), safeSize = Math.min(100, Math.max(1, size));
        StringBuilder where = new StringBuilder(" WHERE " + READER);
        List<Object> arguments = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            String term = "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            where.append(" AND (u.username LIKE ? ESCAPE '!' OR u.real_name LIKE ? ESCAPE '!' OR u.phone LIKE ? ESCAPE '!' OR p.card_no LIKE ? ESCAPE '!')");
            arguments.addAll(List.of(term, term, term, term));
        }
        if (accountStatus != null) {
            if (accountStatus != 0 && accountStatus != 1) throw new IllegalArgumentException("账号状态无效");
            where.append(" AND u.status=?"); arguments.add(accountStatus);
        }
        LocalDate date = LocalDate.now(ZONE);
        if (serviceStatus != null && !serviceStatus.isBlank()) {
            if (!Set.of("WAITING", "ACTIVE", "EXPIRED", "UNKNOWN").contains(serviceStatus))
                throw new IllegalArgumentException("会员服务状态无效");
            where.append(" AND (CASE WHEN p.service_start IS NULL OR p.service_end IS NULL OR p.service_start>p.service_end THEN 'UNKNOWN' "
                    + "WHEN p.service_start>? THEN 'WAITING' WHEN p.service_end<? THEN 'EXPIRED' ELSE 'ACTIVE' END)=?");
            arguments.addAll(List.of(date, date, serviceStatus));
        }
        Long count = jdbc.queryForObject("SELECT COUNT(*)" + FROM + where, Long.class, arguments.toArray());
        arguments.add(safeSize); arguments.add(((long) safePage - 1) * safeSize);
        List<MemberSummary> rows = jdbc.query(COLUMNS + FROM + where + " ORDER BY u.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> summary(rs, date), arguments.toArray());
        return new PageResult<>(rows, count, (long) safePage, (long) safeSize);
    }

    public MemberSummary summary(Long id) {
        List<MemberSummary> rows = jdbc.query(COLUMNS + FROM + " WHERE " + READER + " AND u.id=?",
                (rs, i) -> summary(rs, LocalDate.now(ZONE)), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("会员不存在");
        return rows.get(0);
    }

    public MemberDetail detail(Long id) {
        MemberDetail detail = new MemberDetail();
        BeanUtils.copyProperties(summary(id), detail);
        jdbc.query(COLUMNS + FROM + " WHERE " + READER + " AND u.id=?", rs -> {
            detail.setNotes(rs.getString("notes")); detail.setAddress(rs.getString("address"));
            detail.setSourceKey(rs.getString("source_key"));
            detail.setSourceSystem(first(rs.getString("source_system"), rs.getString("archive_system")));
            detail.setSourceArchiveId(number(rs, "source_archive_id") != null ? number(rs, "source_archive_id") : number(rs, "archive_id"));
            detail.setUpdatedAt(rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime());
            detail.setUpdatedBy(number(rs, "updated_by"));
            detail.setFinancialFacts(new MemberDetail.FinancialFacts(rs.getBigDecimal("paid_fee"), rs.getBigDecimal("deposit_amount"), rs.getString("deposit_status")));
        }, id);
        detail.setChildren(jdbc.query("SELECT * FROM member_child WHERE user_id=? ORDER BY id", (rs, i) -> {
            MemberChildRequest child = new MemberChildRequest();
            child.setId(rs.getLong("id")); child.setName(rs.getString("name")); child.setGender(rs.getString("gender"));
            child.setBirthDate(rs.getDate("birth_date") == null ? null : rs.getDate("birth_date").toLocalDate());
            child.setBirthDatePrecision(rs.getString("birth_date_precision")); child.setSourceBirthText(rs.getString("source_birth_text"));
            child.setSourceKey(rs.getString("source_key")); child.setNotes(rs.getString("notes")); return child;
        }, id));
        detail.setBorrowHistory(jdbc.query("SELECT r.id,r.book_id,b.title,r.borrow_date,r.due_date,r.due_date_kind,r.return_date,r.status "
                + "FROM borrow_record r LEFT JOIN book b ON b.id=r.book_id WHERE r.user_id=? AND r.deleted=0 ORDER BY r.id DESC LIMIT 20",
                (rs, i) -> new MemberDetail.BorrowSummary(rs.getLong("id"), rs.getLong("book_id"), rs.getString("title"),
                        date(rs, "borrow_date"), date(rs, "due_date"), rs.getString("due_date_kind"), date(rs, "return_date"), rs.getString("status")), id));
        return detail;
    }

    private MemberSummary summary(ResultSet rs, LocalDate date) throws SQLException {
        MemberSummary value = new MemberSummary();
        value.setId(rs.getLong("id")); value.setUsername(rs.getString("username")); value.setRealName(rs.getString("real_name"));
        value.setPhone(rs.getString("phone")); value.setEmail(rs.getString("email")); value.setAccountStatus(rs.getInt("account_status"));
        value.setProfilePresent(rs.getObject("user_id") != null); value.setRevision(rs.getLong("account_member_revision"));
        value.setCardNo(rs.getString("card_no")); value.setPlanName(rs.getString("plan_name"));
        value.setServiceStart(date(rs, "service_start")); value.setServiceEnd(date(rs, "service_end"));
        value.setAsOfDate(date);
        LocalDate start = value.getServiceStart(), end = value.getServiceEnd();
        value.setServiceStatus(start == null || end == null || start.isAfter(end) ? "UNKNOWN"
                : date.isBefore(start) ? "WAITING" : date.isAfter(end) ? "EXPIRED" : "ACTIVE");
        value.setUsedUses(rs.getObject("used_uses") == null ? null : rs.getInt("used_uses"));
        value.setTotalUses(rs.getObject("total_uses") == null ? null : rs.getInt("total_uses"));
        String usage = Objects.requireNonNullElse(rs.getString("usage_type"), "UNKNOWN");
        // Compatibility only for verifiably linked Huiben MEMBER archives; no database values are rewritten.
        if (usage.equals("UNKNOWN") && rs.getLong("revision") == 0 && rs.getObject("archive_id") != null)
            usage = value.getTotalUses() == null ? "UNLIMITED" : "LIMITED";
        value.setUsageType(usage);
        if (usage.equals("LIMITED") && value.getTotalUses() != null && value.getUsedUses() != null) {
            value.setRemainingUses((long) value.getTotalUses() - value.getUsedUses());
            value.setOverused(value.getRemainingUses() < 0);
        }
        return value;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MemberDetail create(MemberCreateRequest request, Long operator) {
        validate(request);
        UserRequest account = new UserRequest();
        account.setUsername(request.getUsername()); account.setPassword(request.getPassword());
        account.setRealName(request.getRealName()); account.setPhone(request.getPhone()); account.setEmail(request.getEmail());
        account.setRole("READER"); account.setStatus(1);
        if (!validator.validate(account).isEmpty()) throw new IllegalArgumentException("会员账号资料无效");
        try {
            User created = userService.create(account);
            insertProfile(created.getId(), "manual:" + UUID.randomUUID());
            write(created.getId(), request, 0, operator);
            MemberDetail after = detail(created.getId());
            entitlements.memberFactsChanged(null, after, operator, request.getChangeReason());
            auditWriter.save(created.getId(), operator, request.getChangeReason(), null, after);
            return after;
        } catch (DuplicateKeyException error) {
            throw new MemberConflictException("登录账号或卡号已被使用，请核对后重试");
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MemberDetail update(Long id, MemberUpdateRequest request, Long operator) {
        validate(request);
        User user = users.lockById(id);
        if (user == null || !"READER".equals(user.getRole())) throw new IllegalArgumentException("会员不存在");
        List<Long> locked = jdbc.query("SELECT user_id FROM member_profile WHERE user_id=? FOR UPDATE", (rs, i) -> rs.getLong(1), id);
        MemberDetail before = detail(id);
        if (request.getRevision() == null || request.getRevision() != before.getRevision())
            throw new MemberConflictException("会员资料已被其他操作修改，请刷新后重试");
        try {
            if (locked.isEmpty()) insertProfile(id, "manual:" + UUID.randomUUID());
            reserveCard(before.getCardNo(), id);
            write(id, request, before.getRevision(), operator);
            MemberDetail after = detail(id);
            entitlements.memberFactsChanged(before, after, operator, request.getChangeReason());
            auditWriter.save(id, operator, request.getChangeReason(), before, after);
            return after;
        } catch (DuplicateKeyException error) {
            throw new MemberConflictException("卡号已被使用，请核对后重试");
        }
    }

    private void insertProfile(Long id, String key) {
        jdbc.update("INSERT INTO member_profile(user_id,source_key,usage_type,revision,source_system) VALUES (?,?,'UNKNOWN',0,'manual')", id, key);
    }

    private void write(Long id, MemberWriteRequest request, long revision, Long operator) {
        String card = request.getCardNo() == null || request.getCardNo().isBlank() ? null : request.getCardNo();
        reserveCard(card, id);
        int changed = jdbc.update("UPDATE sys_user SET real_name=?,phone=?,email=?,update_time=CURRENT_TIMESTAMP,member_revision=member_revision+1 "
                        + "WHERE id=? AND deleted=0 AND role='READER' AND member_revision=?",
                request.getRealName(), request.getPhone(), request.getEmail(), id, revision);
        if (changed != 1) throw new MemberConflictException("会员资料已被其他操作修改，请刷新后重试");
        jdbc.update("UPDATE member_profile SET card_no=?,plan_name=?,service_start=?,service_end=?,usage_type=?,used_uses=?,total_uses=?,"
                        + "notes=?,address=?,revision=?,updated_at=CURRENT_TIMESTAMP,updated_by=? WHERE user_id=?",
                card, request.getPlanName(), request.getServiceStart(), request.getServiceEnd(), request.getUsageType(), request.getUsedUses(),
                request.getTotalUses(), request.getNotes(), request.getAddress(), revision + 1, operator, id);
        writeChildren(id, request.getChildren());
    }

    private void reserveCard(String card, Long id) {
        if (card != null && !card.isBlank()) {
            byte[] exact = card.getBytes(StandardCharsets.UTF_8);
            List<Long> owners = jdbc.query("SELECT user_id FROM member_card_history WHERE card_no=?", (rs, i) -> rs.getLong(1), exact);
            if (!owners.isEmpty() && !owners.get(0).equals(id)) throw new MemberConflictException("卡号已被使用，不可重复分配");
            if (owners.isEmpty()) jdbc.update("INSERT INTO member_card_history(card_no,user_id) VALUES (?,?)", exact, id);
        }
    }

    private void writeChildren(Long id, List<MemberChildRequest> children) {
        Set<Long> existing = new HashSet<>(jdbc.query("SELECT id FROM member_child WHERE user_id=? FOR UPDATE", (rs, i) -> rs.getLong(1), id));
        Set<Long> retained = new HashSet<>();
        for (MemberChildRequest child : children) {
            if (child.getId() == null) {
                jdbc.update("INSERT INTO member_child(user_id,name,gender,birth_date,birth_date_precision,source_birth_text,source_key,notes) VALUES (?,?,?,?,?,?,?,?)",
                        id, child.getName(), child.getGender(), child.getBirthDate(), child.getBirthDatePrecision(), child.getSourceBirthText(), child.getSourceKey(), child.getNotes());
            } else {
                if (!existing.contains(child.getId()) || !retained.add(child.getId())) throw new IllegalArgumentException("宝宝记录不属于该会员或重复");
                jdbc.update("UPDATE member_child SET name=?,gender=?,birth_date=?,birth_date_precision=?,source_birth_text=?,source_key=?,notes=? WHERE id=? AND user_id=?",
                        child.getName(), child.getGender(), child.getBirthDate(), child.getBirthDatePrecision(), child.getSourceBirthText(), child.getSourceKey(), child.getNotes(), child.getId(), id);
            }
        }
        for (Long old : existing) if (!retained.contains(old)) jdbc.update("DELETE FROM member_child WHERE id=? AND user_id=?", old, id);
    }

    private void validate(MemberWriteRequest request) {
        if (!validator.validate(request).isEmpty()) throw new IllegalArgumentException("会员资料无效");
        if (request.getServiceStart() != null && request.getServiceEnd() != null && request.getServiceStart().isAfter(request.getServiceEnd()))
            throw new IllegalArgumentException("服务开始日不能晚于结束日");
        if ("LIMITED".equals(request.getUsageType()) && request.getTotalUses() == null)
            throw new IllegalArgumentException("有限次数必须填写总次数");
        if ("UNLIMITED".equals(request.getUsageType()) && request.getTotalUses() != null)
            throw new IllegalArgumentException("不限次数不能填写有限总次数");
        Set<String> childSources = new HashSet<>();
        for (MemberChildRequest child : request.getChildren()) {
            if (child.getSourceKey() != null && !child.getSourceKey().isEmpty() && !childSources.add(child.getSourceKey()))
                throw new IllegalArgumentException("宝宝来源键重复，请核对后重试");
            if (child.getBirthDate() != null && (!"DAY".equals(child.getBirthDatePrecision()) || child.getBirthDate().isAfter(LocalDate.now(ZONE))))
                throw new IllegalArgumentException("宝宝生日须保留已知精度且不能晚于当天");
            if ("DAY".equals(child.getBirthDatePrecision()) && child.getBirthDate() == null)
                throw new IllegalArgumentException("已知日精度须填写生日");
            if (Set.of("MONTH", "YEAR").contains(child.getBirthDatePrecision()) && (child.getSourceBirthText() == null || child.getSourceBirthText().isBlank()))
                throw new IllegalArgumentException("非日精度须保留来源日期文本");
        }
    }

    public PageResult<Map<String, Object>> changes(Long id, int page, int size) {
        summary(id);
        int p = Math.max(1, page), s = Math.min(100, Math.max(1, size));
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM member_profile_change WHERE user_id=?", Long.class, id);
        var rows = jdbc.query("SELECT * FROM member_profile_change WHERE user_id=? ORDER BY id DESC LIMIT ? OFFSET ?", (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id")); row.put("userId", rs.getLong("user_id")); row.put("operatorId", rs.getLong("operator_id"));
            row.put("changeSource", rs.getString("change_source")); row.put("changeReason", rs.getString("change_reason"));
            row.put("beforeJson", rs.getString("before_json")); row.put("afterJson", rs.getString("after_json"));
            row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime()); return row;
        }, id, s, ((long) p - 1) * s);
        return new PageResult<>(rows, total, (long) p, (long) s);
    }

    public Map<String, Object> sourcePreview(Long id) {
        MemberDetail current = detail(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceAvailable", false); result.put("sourceKey", current.getSourceKey()); result.put("archiveId", current.getSourceArchiveId());
        LocalDate asOfDate = LocalDate.now(ZONE);
        result.put("asOfDate", asOfDate);
        result.put("fields", List.of()); result.put("childCandidates", List.of()); result.put("unmappedFields", List.of());
        if (!"huiben".equals(current.getSourceSystem()) || current.getSourceArchiveId() == null) return result;
        List<String> archives = jdbc.query("SELECT details_json FROM legacy_record WHERE id=? AND user_id=? AND source_system='huiben' AND record_type='MEMBER' AND CONCAT('huiben:',source_key)=?",
                (rs, i) -> rs.getString(1), current.getSourceArchiveId(), id, current.getSourceKey());
        if (archives.size() != 1) throw new IllegalArgumentException("原始档案关联不一致");
        try {
            JsonNode archive = json.readTree(archives.get(0));
            if (!archive.isObject()) throw new IllegalArgumentException("原始档案格式无效");
            MemberSourcePreviewBuilder.populate(result, archive, current, json, asOfDate);
            return result;
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("原始档案格式无效");
        }
    }

    private static String first(String a, String b) { return a != null ? a : b; }
    private static Long number(ResultSet rs, String column) throws SQLException { return rs.getObject(column) == null ? null : rs.getLong(column); }
    private static LocalDate date(ResultSet rs, String column) throws SQLException { return rs.getDate(column) == null ? null : rs.getDate(column).toLocalDate(); }
}
