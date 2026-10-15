package com.library.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.common.PageResult;
import com.library.dto.MembershipPlanDefinition;
import com.library.dto.MembershipPlanDefinition.*;
import com.library.dto.MembershipPlanRequests.*;
import com.library.dto.MembershipPlanResponses.*;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/** All writes are confined to the three membership_plan tables; publishing never grants member rights. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MembershipPlanService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Validator validator;
    private final MembershipPlanAuditWriter audit;
    private static final String SUMMARY = "SELECT p.*,(SELECT COUNT(*) FROM membership_plan_version v WHERE v.plan_id=p.id) AS version_count FROM membership_plan p";

    public PageResult<Summary> list(int page, int size, String keyword) {
        int p = Math.max(1, page), s = Math.min(100, Math.max(1, size));
        String filter = keyword == null || keyword.isBlank() ? "" : " WHERE p.name LIKE ? ESCAPE '!'";
        List<Object> args = new ArrayList<>();
        if (!filter.isEmpty()) args.add("%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM membership_plan p" + filter, Long.class, args.toArray());
        args.add(s); args.add(((long) p - 1) * s);
        var rows = jdbc.query(SUMMARY + filter + " ORDER BY p.id DESC LIMIT ? OFFSET ?", (rs, i) -> summary(rs), args.toArray());
        return new PageResult<>(rows, count, (long) p, (long) s);
    }

    public Detail detail(Long id) {
        var rows = jdbc.query(SUMMARY + " WHERE p.id=?", (rs, i) -> new AbstractMap.SimpleEntry<>(summary(rs), read(rs.getString("draft_json"))), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("套餐不存在");
        Summary p = rows.get(0).getKey();
        List<Version> versions = jdbc.query("SELECT * FROM membership_plan_version WHERE plan_id=? ORDER BY version_number DESC", (rs, i) -> version(rs), id);
        return new Detail(p.id(), p.name(), p.revision(), p.enabled(), p.selectedVersionId(), p.versionCount(),
                p.updatedAt(), p.updatedBy(), rows.get(0).getValue(), versions);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Detail create(Create request, Long operator) {
        validate(request, false);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO membership_plan(name,draft_json,updated_by) VALUES (?,?,?)", new String[]{"id"});
            statement.setString(1, request.getName()); statement.setString(2, encode(definition(request))); statement.setLong(3, operator);
            return statement;
        }, key);
        Long id = Objects.requireNonNull(key.getKey()).longValue();
        Detail after = detail(id);
        audit.save(id, operator, "CREATE_DRAFT", request.getChangeReason(), null, after);
        return after;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Detail saveDraft(Long id, Draft request, Long operator) {
        validate(request, false);
        lock(id); Detail before = detail(id); revision(before, request.getRevision());
        jdbc.update("UPDATE membership_plan SET name=?,draft_json=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP,updated_by=? WHERE id=?",
                request.getName(), encode(definition(request)), operator, id);
        Detail after = detail(id);
        audit.save(id, operator, "SAVE_DRAFT", request.getChangeReason(), before, after);
        return after;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Publication publish(Long id, Publish request, Long operator) {
        validated(request);
        lock(id);
        byte[] key = request.getIdempotencyKey().getBytes(StandardCharsets.US_ASCII);
        String hash = hash(encode(request));
        var replay = jdbc.query("SELECT * FROM membership_plan_version WHERE plan_id=? AND idempotency_key=?", (rs, i) -> {
            if (!hash.equals(rs.getString("request_hash"))) throw new MembershipPlanConflictException("发布标识已用于不同请求，请核对后重试");
            return version(rs);
        }, id, key);
        // Retry detection deliberately precedes revision/current-draft checks.
        if (!replay.isEmpty()) return new Publication(replay.get(0), detail(id).revision(), true);
        Detail before = detail(id); revision(before, request.getRevision()); validate(before.draft(), true);
        long next = jdbc.queryForObject("SELECT COALESCE(MAX(version_number),0)+1 FROM membership_plan_version WHERE plan_id=?", Long.class, id);
        GeneratedKeyHolder generated = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO membership_plan_version(plan_id,version_number,definition_json,confirmation_basis,idempotency_key,request_hash,created_by) VALUES (?,?,?,?,?,?,?)", new String[]{"id"});
            statement.setLong(1, id); statement.setLong(2, next); statement.setString(3, encode(before.draft()));
            statement.setString(4, request.getConfirmationBasis()); statement.setBytes(5, key); statement.setString(6, hash); statement.setLong(7, operator);
            return statement;
        }, generated);
        jdbc.update("UPDATE membership_plan SET revision=revision+1,updated_at=CURRENT_TIMESTAMP,updated_by=? WHERE id=?", operator, id);
        Detail after = detail(id);
        audit.save(id, operator, "PUBLISH_VERSION", request.getChangeReason(), before, after);
        Long versionId = Objects.requireNonNull(generated.getKey()).longValue();
        return new Publication(after.versions().stream().filter(v -> v.id().equals(versionId)).findFirst().orElseThrow(), after.revision(), false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Detail select(Long id, Selection request, Long operator) {
        validated(request);
        lock(id); Detail before = detail(id); revision(before, request.getRevision());
        if (before.versions().stream().noneMatch(v -> v.id().equals(request.getVersionId())))
            throw new IllegalArgumentException("请选择该套餐已发布的版本");
        jdbc.update("UPDATE membership_plan SET selected_version_id=?,enabled=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP,updated_by=? WHERE id=?",
                request.getVersionId(), request.getEnabled(), operator, id);
        Detail after = detail(id);
        audit.save(id, operator, request.getEnabled() ? "SELECT_VERSION" : "DISABLE_SELECTION", request.getChangeReason(), before, after);
        return after;
    }

    public PageResult<Change> changes(Long id, int page, int size) {
        detail(id);
        int p = Math.max(1, page), s = Math.min(100, Math.max(1, size));
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM membership_plan_change WHERE plan_id=?", Long.class, id);
        var rows = jdbc.query("SELECT * FROM membership_plan_change WHERE plan_id=? ORDER BY id DESC LIMIT ? OFFSET ?", (rs, i) ->
                new Change(rs.getLong("id"), rs.getLong("plan_id"), rs.getLong("operator_id"), rs.getString("action"), rs.getString("change_reason"),
                        rs.getString("before_json"), rs.getString("after_json"), rs.getTimestamp("created_at").toLocalDateTime()), id, s, ((long) p - 1) * s);
        return new PageResult<>(rows, total, (long) p, (long) s);
    }

    private void lock(Long id) {
        var ids = jdbc.query("SELECT id FROM membership_plan WHERE id=? FOR UPDATE", (rs, i) -> rs.getLong(1), id);
        if (ids.isEmpty()) throw new IllegalArgumentException("套餐不存在");
    }
    private void revision(Detail before, Long expected) {
        if (expected == null || before.revision() != expected) throw new MembershipPlanConflictException("套餐已被其他操作修改，请刷新后重试");
    }
    private Summary summary(ResultSet rs) throws SQLException {
        return new Summary(rs.getLong("id"), rs.getString("name"), rs.getLong("revision"), rs.getBoolean("enabled"),
                rs.getObject("selected_version_id") == null ? null : rs.getLong("selected_version_id"), rs.getLong("version_count"),
                rs.getTimestamp("updated_at").toLocalDateTime(), rs.getLong("updated_by"));
    }
    private Version version(ResultSet rs) throws SQLException {
        MembershipPlanDefinition definition = read(rs.getString("definition_json"));
        return new Version(rs.getLong("id"), rs.getLong("plan_id"), rs.getLong("version_number"), definition.getName(), definition.getNotes(),
                definition.getSourceReferences(), definition.getRules(), rs.getString("confirmation_basis"),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getLong("created_by"));
    }
    private MembershipPlanDefinition definition(MembershipPlanDefinition request) {
        MembershipPlanDefinition definition = new MembershipPlanDefinition();
        definition.setName(request.getName()); definition.setNotes(request.getNotes()); definition.setRules(request.getRules());
        definition.setSourceReferences(request.getSourceReferences()); return definition;
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("套餐资料序列化失败"); }
    }
    private MembershipPlanDefinition read(String value) {
        try { return json.readValue(value, MembershipPlanDefinition.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException("套餐资料读取失败"); }
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("套餐请求校验失败"); }
    }
    private void validated(Object request) {
        if (!validator.validate(request).isEmpty()) throw new IllegalArgumentException("套餐资料校验失败");
    }
    private void validate(MembershipPlanDefinition definition, boolean publishing) {
        validated(definition);
        Rules rules = definition.getRules();
        limit(rules.getPerOrderLimit(), publishing); limit(rules.getActiveLoanLimit(), publishing);
        if ("LIMITED".equals(rules.getUsageType()) ? rules.getTotalUses() == null : rules.getTotalUses() != null)
            throw new IllegalArgumentException("次数类型与总次数不一致");
        if (!"DAYS".equals(rules.getDueDateType()) && rules.getBorrowDays() != null)
            throw new IllegalArgumentException("归还日期类型与天数不一致");
        if (!"ALLOWED".equals(rules.getRenewalPermission()) && (rules.getRenewalLimit() != null || rules.getRenewalDays() != null))
            throw new IllegalArgumentException("不适用的续借设置应留空");
        if (rules.getRenewalLimit() != null) limit(rules.getRenewalLimit(), publishing);
        choice(rules.getLanguages(), publishing); choice(rules.getPickupMethods(), publishing);
        if (publishing && (rules.getServiceDurationDays() == null || "UNKNOWN".equals(rules.getUsageType())
                || "UNKNOWN".equals(rules.getDueDateType()) || "UNKNOWN".equals(rules.getRenewalPermission())
                || "DAYS".equals(rules.getDueDateType()) && rules.getBorrowDays() == null
                || "ALLOWED".equals(rules.getRenewalPermission()) && (rules.getRenewalLimit() == null || rules.getRenewalDays() == null)))
            throw new IllegalArgumentException("发布前须明确核实全部适用规则");
    }
    private void limit(Limit value, boolean publishing) {
        if ("LIMITED".equals(value.getMode()) ? value.getValue() == null : value.getValue() != null)
            throw new IllegalArgumentException("限额模式与数值不一致");
        if (publishing && "UNKNOWN".equals(value.getMode())) throw new IllegalArgumentException("发布前须明确核实限额");
    }
    private void choice(Choice value, boolean publishing) {
        if ("ALLOW_LIST".equals(value.getMode()) ? value.getValues().isEmpty() : !value.getValues().isEmpty())
            throw new IllegalArgumentException("选项模式与列表不一致");
        if (new HashSet<>(value.getValues()).size() != value.getValues().size()) throw new IllegalArgumentException("选项列表不能重复");
        if (publishing && "UNKNOWN".equals(value.getMode())) throw new IllegalArgumentException("发布前须明确核实语种和取书方式");
    }
}
