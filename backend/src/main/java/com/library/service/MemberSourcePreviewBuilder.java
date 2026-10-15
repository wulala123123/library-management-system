package com.library.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.dto.MemberChildRequest;
import com.library.dto.MemberDetail;
import com.library.dto.MemberSourceChildCandidate;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Only explicit archive keys are interpreted. Building a proposal never writes or merges children. */
final class MemberSourcePreviewBuilder {
    private MemberSourcePreviewBuilder() {}

    static void populate(Map<String, Object> result, JsonNode archive, MemberDetail current,
                         ObjectMapper json, LocalDate asOfDate) {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("realName", "会员名字"); mapping.put("phone", "手机号"); mapping.put("cardNo", "卡号");
        mapping.put("planName", "借阅计划类型"); mapping.put("serviceStart", "服务开始时间"); mapping.put("serviceEnd", "服务结束时间");
        mapping.put("usedUses", "已借阅次数"); mapping.put("totalUses", "总次数");
        JsonNode currentJson = json.valueToTree(current);
        Set<String> mapped = new HashSet<>();
        List<Map<String, Object>> fields = new ArrayList<>();
        mapping.forEach((field, sourceField) -> {
            if (!archive.has(sourceField)) return;
            Map<String, Object> item = scalar(field, sourceField, currentJson.get(field), archive.get(sourceField));
            boolean total = field.equals("totalUses");
            item.put("reviewOnly", total);
            if (total) { item.put("selectable", false); item.put("reasonCode", "REVIEW_ONLY_TOTAL_USES"); }
            fields.add(item); mapped.add(sourceField);
        });
        for (String[] extra : List.of(new String[]{"address", "地址", "500"}, new String[]{"notes", "备注", "2000"})) {
            if (!archive.has(extra[1])) continue;
            JsonNode source = archive.get(extra[1]);
            String reason = textReason(source, Integer.parseInt(extra[2]));
            boolean selectable = reason.equals("READY");
            Map<String, Object> item = scalar(extra[0], extra[1], currentJson.get(extra[0]), source);
            item.put("selectable", selectable); item.put("reasonCode", reason); item.put("reviewOnly", !selectable);
            fields.add(item);
            if (selectable) mapped.add(extra[1]);
        }
        requireHuibenKey(current.getSourceKey());
        List<MemberSourceChildCandidate> children = new ArrayList<>();
        List<String> prefixes = List.of("宝宝", "二宝", "三宝");
        for (int index = 0; index < prefixes.size(); index++) {
            String prefix = prefixes.get(index);
            List<String> sourceFields = List.of(prefix + "名字", prefix + "生日", prefix + "性别", prefix + "年龄");
            MemberSourceChildCandidate candidate = child(archive, current, sourceFields, index + 1, asOfDate);
            children.add(candidate);
            if (candidate.selectable()) {
                // Only nonblank fields actually eligible for copying leave the unmapped list.
                for (String field : sourceFields.subList(0, 3)) {
                    if (text(archive.get(field)) != null) mapped.add(field);
                }
            }
        }
        List<String> unmapped = new ArrayList<>();
        archive.fieldNames().forEachRemaining(key -> { if (!mapped.contains(key)) unmapped.add(key); });
        result.put("sourceAvailable", true); result.put("fields", fields);
        result.put("childCandidates", children); result.put("unmappedFields", unmapped);
    }

    private static Map<String, Object> scalar(String field, String sourceField, JsonNode current, JsonNode source) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("field", field); item.put("sourceField", sourceField);
        item.put("currentValue", current); item.put("sourceValue", source);
        item.put("conflict", !equivalent(current, source));
        return item;
    }

    private static MemberSourceChildCandidate child(JsonNode archive, MemberDetail current, List<String> fields,
                                                    int slot, LocalDate asOfDate) {
        String sourceKey = current.getSourceKey() + ":child:" + slot;
        JsonNode nameNode = archive.get(fields.get(0)), birthNode = archive.get(fields.get(1));
        JsonNode genderNode = archive.get(fields.get(2)), ageNode = archive.get(fields.get(3));
        String name = text(nameNode), rawBirth = text(birthNode), gender = text(genderNode), age = text(ageNode);
        String reason = "READY";
        for (JsonNode value : Arrays.asList(nameNode, birthNode, genderNode, ageNode)) {
            if (value != null && !value.isNull() && !value.isTextual()) reason = "INVALID_SOURCE_TYPE";
        }
        if (reason.equals("READY") && (tooLong(name, 100) || tooLong(rawBirth, 300) || tooLong(gender, 50) || tooLong(age, 300)))
            reason = "SOURCE_TOO_LONG";
        LocalDate birth = null;
        if (reason.equals("READY") && rawBirth != null) {
            try {
                if (!rawBirth.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeParseException("Not an explicit ISO day", "", 0);
                birth = LocalDate.parse(rawBirth);
                if (birth.getYear() < 1000) throw new DateTimeParseException("Outside MySQL DATE range", "", 0);
                if (birth.isAfter(asOfDate)) { reason = "FUTURE_SOURCE_DATE"; birth = null; }
            } catch (DateTimeParseException invalid) { reason = "INVALID_SOURCE_DATE"; birth = null; }
        }
        if (reason.equals("READY") && name == null && birth == null)
            reason = gender == null && age == null ? "SOURCE_EMPTY" : "INSUFFICIENT_CHILD_IDENTITY";
        List<MemberChildRequest> matched = Objects.requireNonNullElse(current.getChildren(), List.<MemberChildRequest>of()).stream()
                .filter(c -> sourceKey.equals(c.getSourceKey())).toList();
        MemberChildRequest existing = matched.size() == 1 ? matched.get(0) : null;
        if (matched.size() > 1) reason = "DUPLICATE_CURRENT_SOURCE_KEY";
        // Unknown or malformed dates are retained only as source text, never fabricated as a day.
        var value = new MemberSourceChildCandidate.Value(name, gender, birth, birth == null ? "UNKNOWN" : "DAY",
                rawBirth, sourceKey, null);
        boolean conflict = existing != null && ((name != null && !Objects.equals(name, existing.getName()))
                || (gender != null && !Objects.equals(gender, existing.getGender()))
                || (birth != null && !Objects.equals(birth, existing.getBirthDate()))
                || (rawBirth != null && !Objects.equals(rawBirth, existing.getSourceBirthText())));
        return new MemberSourceChildCandidate(sourceKey, slot, fields, existing, value,
                reason.equals("READY"), reason, conflict, age);
    }

    private static void requireHuibenKey(String key) {
        if (key == null || !key.startsWith("huiben:") || key.length() <= "huiben:".length()
                || key.length() + ":child:3".length() > 128)
            throw new IllegalArgumentException("原始档案来源键无效");
    }
    private static boolean tooLong(String value, int maximum) { return value != null && value.length() > maximum; }
    private static String text(JsonNode value) {
        return value != null && value.isTextual() && !value.textValue().isBlank() ? value.textValue() : null;
    }
    private static String textReason(JsonNode value, int maximum) {
        if (value == null || value.isNull() || value.isTextual() && value.textValue().isBlank()) return "SOURCE_EMPTY";
        if (!value.isTextual()) return "INVALID_SOURCE_TYPE";
        return tooLong(value.textValue(), maximum) ? "SOURCE_TOO_LONG" : "READY";
    }
    private static boolean equivalent(JsonNode a, JsonNode b) {
        return a == null ? b == null || b.isNull() : a.isNull() ? b == null || b.isNull() : b != null && a.asText().equals(b.asText());
    }
}
