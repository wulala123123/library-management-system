package com.library.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.library.common.PageResult;
import com.library.dto.*;
import com.library.dto.MemberEntitlementContracts.*;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MemberEntitlementService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Validator validator;
    @Autowired @Lazy private MemberService members;
    private final MembershipPlanService plans;
    private final MemberEntitlementAuditWriter audit;

    public MemberSummary summary(Long id) { return members.summary(id); }
    public AdminView current(Long id) {
        MemberSummary member = members.summary(id);
        Snapshot snapshot = currentSnapshot(id);
        return new AdminView(member, currentRules(member, snapshot), snapshot);
    }
    public ReaderView reader(Long id) {
        AdminView view = current(id);
        return new ReaderView(view.member(), view.effectiveRules(), view.snapshot() != null);
    }
    public Preview preview(Long id, Input input) {
        validated(input);
        Preview preview = previewFrom(current(id), input);
        validateHistoricalBasis(preview, input);
        return preview;
    }

    private Preview previewFrom(AdminView current, Input input) {
        if (current.member().getRevision() != input.getRevision()) throw conflict("会员资料已变化，请重新预览");
        validateActual(input.getActual(), input.getRules());
        VersionBasis basis = basis(input.getPlanVersionId());
        Map<String, Object> bound = new LinkedHashMap<>();
        bound.put("memberId", current.member().getId()); bound.put("revision", current.member().getRevision());
        bound.put("facts", actual(current.member())); bound.put("profilePresent", current.member().isProfilePresent());
        bound.put("snapshotId", current.snapshot() == null ? null : current.snapshot().id());
        bound.put("version", basis.version()); bound.put("historical", basis.historical()); bound.put("input", plain(input));
        bound.put("planEnabled", basis.enabled()); bound.put("selectedVersionId", basis.selectedVersionId());
        List<Difference> diffs = new ArrayList<>();
        JsonNode before = json.valueToTree(actual(current.member())), proposed = json.valueToTree(input.getActual());
        JsonNode version = basis.version() == null ? NullNode.getInstance() : json.valueToTree(basis.version());
        proposed.fieldNames().forEachRemaining(field -> diffs.add(new Difference("actual." + field, before.path(field), proposed.path(field), version.path("rules").path(field))));
        JsonNode rulesBefore = json.valueToTree(current.effectiveRules()), rulesProposed = json.valueToTree(input.getRules());
        rulesProposed.fieldNames().forEachRemaining(field -> diffs.add(new Difference("rules." + field, rulesBefore.path(field), rulesProposed.path(field), version.path("rules").path(field))));
        return new Preview(hash(bound), current, new Proposed(input.getActual(), input.getRules(), input.getPlanVersionId()), basis.version(), diffs, basis.historical());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MemberEntitlementContracts.Result confirm(Long id, Confirmation input, Long operator) {
        validated(input);
        // The same user lock is the serialization point for member, account and Profile writes.
        var locked = jdbc.query("SELECT id FROM sys_user WHERE id=? AND deleted=0 AND role='READER' FOR UPDATE", (rs, i) -> rs.getLong(1), id);
        if (locked.isEmpty()) throw new IllegalArgumentException("会员不存在");
        String requestHash = hash(input);
        byte[] key = input.getIdempotencyKey().getBytes(StandardCharsets.US_ASCII);
        var repeated = jdbc.query("SELECT request_hash,snapshot_id,resulting_revision FROM member_entitlement_request WHERE user_id=? AND idempotency_key=?", (rs, i) -> {
            if (!requestHash.equals(rs.getString("request_hash"))) throw conflict("确认标识已用于不同请求，请重新核对");
            return new MemberEntitlementContracts.Result(snapshot(id, rs.getLong("snapshot_id")), rs.getLong("resulting_revision"), true);
        }, id, key);
        if (!repeated.isEmpty()) return repeated.get(0);
        // Keep selection/disable changes serialized until the confirmation transaction commits.
        if (input.getPlanVersionId() != null) {
            var planIds = jdbc.query("SELECT plan_id FROM membership_plan_version WHERE id=?", (rs, i) -> rs.getLong(1), input.getPlanVersionId());
            if (planIds.isEmpty()) throw new IllegalArgumentException("只能关联已经发布并保留的套餐版本");
            jdbc.query("SELECT id FROM membership_plan WHERE id=? FOR UPDATE", (rs, i) -> rs.getLong(1), planIds.get(0));
        }
        AdminView before = current(id);
        Preview preview = previewFrom(before, input);
        if (!preview.previewHash().equals(input.getPreviewHash())) throw conflict("权益预览已过期，请重新预览后确认");
        validateHistoricalBasis(preview, input);
        MemberDetail details = members.detail(id);
        MemberUpdateRequest update = new MemberUpdateRequest();
        BeanUtils.copyProperties(details, update);
        update.setRevision(input.getRevision()); update.setChangeReason(input.getReason());
        Actual actual = input.getActual();
        update.setPlanName(actual.planName()); update.setServiceStart(actual.serviceStart()); update.setServiceEnd(actual.serviceEnd());
        update.setUsageType(actual.usageType()); update.setUsedUses(actual.usedUses()); update.setTotalUses(actual.totalUses());
        // The unified member transaction creates missing profiles and updates the sole current facts.
        boolean priorConfirmation = confirmationActive();
        CONFIRMATION.set(true);
        try { members.update(id, update, operator); }
        finally { if (priorConfirmation) CONFIRMATION.set(true); else CONFIRMATION.remove(); }
        MemberSummary after = members.summary(id);
        Snapshot snapshot = persist(id, after.getRevision(), input.getPlanVersionId(), preview.version(), actual(after),
                input.getRules(), input.getSourceReferences(), input.getReason(), input.getHistoryBasis(), operator);
        audit.save(id, operator, "CONFIRM_HISTORICAL_ENTITLEMENT", input.getReason(), before, new AdminView(after, input.getRules(), snapshot));
        jdbc.update("INSERT INTO member_entitlement_request(user_id,idempotency_key,request_hash,snapshot_id,resulting_revision) VALUES (?,?,?,?,?)",
                id, key, requestHash, snapshot.id(), after.getRevision());
        return new MemberEntitlementContracts.Result(snapshot, after.getRevision(), false);
    }

    public PageResult<Snapshot> history(Long id, int page, int size) {
        members.summary(id);
        int p = Math.max(1, page), s = Math.min(100, Math.max(1, size));
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM member_entitlement_snapshot WHERE user_id=?", Long.class, id);
        var rows = jdbc.query("SELECT * FROM member_entitlement_snapshot WHERE user_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, i) -> readSnapshot(rs), id, s, ((long) p - 1) * s);
        return new PageResult<>(rows, total, (long) p, (long) s);
    }

    private Snapshot currentSnapshot(Long id) {
        var rows = jdbc.query("SELECT s.* FROM member_profile p JOIN member_entitlement_snapshot s ON s.id=p.current_entitlement_snapshot_id AND s.user_id=p.user_id WHERE p.user_id=?",
                (rs, i) -> readSnapshot(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }
    private Snapshot snapshot(Long id, Long snapshotId) {
        var rows = jdbc.query("SELECT * FROM member_entitlement_snapshot WHERE user_id=? AND id=?", (rs, i) -> readSnapshot(rs), id, snapshotId);
        if (rows.isEmpty()) throw new IllegalStateException("权益快照关联无效"); return rows.get(0);
    }
    private Snapshot persist(Long id, long revision, Long versionId, MembershipPlanResponses.Version version, Actual actual,
            MembershipPlanDefinition.Rules rules, List<String> references, String reason, String historyBasis, Long operator) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("权益快照必须参与会员事务");
        GeneratedKeyHolder generated = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO member_entitlement_snapshot(user_id,member_revision,plan_version_id,version_json,facts_json,rules_json,source_references_json,reason,history_basis,confirmed_by) VALUES (?,?,?,?,?,?,?,?,?,?)", new String[]{"id"});
            statement.setLong(1, id); statement.setLong(2, revision); statement.setObject(3, versionId);
            statement.setString(4, version == null ? null : encode(version)); statement.setString(5, encode(actual)); statement.setString(6, encode(rules));
            statement.setString(7, encode(references)); statement.setString(8, reason); statement.setString(9, historyBasis); statement.setLong(10, operator); return statement;
        }, generated);
        Long snapshotId = Objects.requireNonNull(generated.getKey()).longValue();
        if (jdbc.update("UPDATE member_profile SET current_entitlement_snapshot_id=? WHERE user_id=?", snapshotId, id) != 1)
            throw new IllegalStateException("会员权益快照保存失败");
        return snapshot(id, snapshotId);
    }

    /** Integration hook: called by the single member save path after facts changed, never for contact-only edits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void memberFactsChanged(MemberDetail before, MemberDetail after, Long operator, String reason) {
        if (confirmationActive() || (before == null && allUnknown(after))
                || (before != null && actual(before).equals(actual(after)))) return;
        Snapshot old = currentSnapshot(after.getId());
        MembershipPlanDefinition.Rules rules = currentRules(after, old);
        Snapshot next = persist(after.getId(), after.getRevision(), old == null ? null : old.planVersionId(),
                old == null ? null : old.versionSnapshot(), actual(after), rules,
                old == null ? List.of() : old.sourceReferences(), reason, old == null ? null : old.historyBasis(), operator);
        audit.save(after.getId(), operator, "MEMBER_FACTS_REVISION", reason, old, next);
    }

    private static final ThreadLocal<Boolean> CONFIRMATION = new ThreadLocal<>();
    private boolean confirmationActive() { return Boolean.TRUE.equals(CONFIRMATION.get()); }
    private boolean allUnknown(MemberSummary value) {
        return value.getPlanName() == null && value.getServiceStart() == null && value.getServiceEnd() == null
                && "UNKNOWN".equals(value.getUsageType()) && value.getUsedUses() == null && value.getTotalUses() == null;
    }

    private void validateHistoricalBasis(Preview preview, Input input) {
        if (preview.historicalVersion() && (input.getHistoryBasis() == null || input.getHistoryBasis().isBlank()))
            throw new IllegalArgumentException("关联历史非当前可选版本必须填写历史依据");
    }
    private record VersionBasis(MembershipPlanResponses.Version version, boolean historical, Boolean enabled, Long selectedVersionId) {}
    private VersionBasis basis(Long id) {
        if (id == null) return new VersionBasis(null, false, null, null);
        List<Long> planIds = jdbc.query("SELECT plan_id FROM membership_plan_version WHERE id=?", (rs, i) -> rs.getLong(1), id);
        if (planIds.isEmpty()) throw new IllegalArgumentException("只能关联已经发布并保留的套餐版本");
        var plan = plans.detail(planIds.get(0));
        return new VersionBasis(plan.versions().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow(),
                !plan.enabled() || !id.equals(plan.selectedVersionId()), plan.enabled(), plan.selectedVersionId());
    }
    private MembershipPlanDefinition.Rules currentRules(MemberSummary member, Snapshot snapshot) {
        MembershipPlanDefinition.Rules rules = snapshot == null ? new MembershipPlanDefinition.Rules()
                : decode(encode(snapshot.rulesAtConfirmation()), MembershipPlanDefinition.Rules.class);
        rules.setUsageType(member.getUsageType());
        // A known raw total with UNKNOWN semantics remains solely in current actual facts.
        rules.setTotalUses("LIMITED".equals(member.getUsageType()) ? member.getTotalUses() : null);
        return rules;
    }
    private Actual actual(MemberSummary member) { return new Actual(member.getPlanName(), member.getServiceStart(), member.getServiceEnd(), member.getUsageType(), member.getUsedUses(), member.getTotalUses()); }
    private Input plain(Input source) {
        Input value = new Input(); BeanUtils.copyProperties(source, value); return value;
    }
    private void validated(Object value) { if (!validator.validate(value).isEmpty()) throw new IllegalArgumentException("权益核对资料无效"); }
    private void validateActual(Actual actual, MembershipPlanDefinition.Rules rules) {
        if (actual.serviceStart() != null && actual.serviceEnd() != null && actual.serviceStart().isAfter(actual.serviceEnd()))
            throw new IllegalArgumentException("实际服务起止日倒置，须先核实");
        if ("LIMITED".equals(actual.usageType()) && actual.totalUses() == null || "UNLIMITED".equals(actual.usageType()) && actual.totalUses() != null)
            throw new IllegalArgumentException("实际次数类型与总次数不一致");
        if (!actual.usageType().equals(rules.getUsageType()) || !Objects.equals(rules.getTotalUses(), "LIMITED".equals(actual.usageType()) ? actual.totalUses() : null))
            throw new IllegalArgumentException("规则次数须与实际权益一致，未知原值只保留在实际资料");
        validateLimit(rules.getPerOrderLimit()); validateLimit(rules.getActiveLoanLimit());
        if (rules.getRenewalLimit() != null) validateLimit(rules.getRenewalLimit());
        if (!"DAYS".equals(rules.getDueDateType()) && rules.getBorrowDays() != null) throw new IllegalArgumentException("归还日期类型与天数不一致");
        if (!"ALLOWED".equals(rules.getRenewalPermission()) && (rules.getRenewalLimit() != null || rules.getRenewalDays() != null)) throw new IllegalArgumentException("不适用续借设置须留空");
        validateChoice(rules.getLanguages()); validateChoice(rules.getPickupMethods());
    }
    private void validateLimit(MembershipPlanDefinition.Limit value) {
        if ("LIMITED".equals(value.getMode()) ? value.getValue() == null : value.getValue() != null) throw new IllegalArgumentException("限额模式与值不一致");
    }
    private void validateChoice(MembershipPlanDefinition.Choice value) {
        if ("ALLOW_LIST".equals(value.getMode()) ? value.getValues().isEmpty() : !value.getValues().isEmpty()) throw new IllegalArgumentException("选项模式与列表不一致");
        if (new HashSet<>(value.getValues()).size() != value.getValues().size()) throw new IllegalArgumentException("选项不能重复");
    }
    private Snapshot readSnapshot(ResultSet rs) throws SQLException {
        return new Snapshot(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("member_revision"),
                rs.getObject("plan_version_id") == null ? null : rs.getLong("plan_version_id"),
                rs.getString("version_json") == null ? null : decode(rs.getString("version_json"), MembershipPlanResponses.Version.class),
                decode(rs.getString("facts_json"), Actual.class), decode(rs.getString("rules_json"), MembershipPlanDefinition.Rules.class),
                decodeList(rs.getString("source_references_json")), rs.getString("reason"), rs.getString("history_basis"), rs.getTimestamp("confirmed_at").toLocalDateTime(), rs.getLong("confirmed_by"));
    }
    private List<String> decodeList(String value) {
        try { return json.readValue(value, new TypeReference<>() {}); }
        catch (JsonProcessingException error) { throw new IllegalStateException("权益来源读取失败"); }
    }
    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException error) { throw new IllegalStateException("权益快照读取失败"); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("权益资料序列化失败"); }
    }
    private String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("权益请求校验失败"); }
    }
    private MemberEntitlementConflictException conflict(String message) { return new MemberEntitlementConflictException(message); }
}
