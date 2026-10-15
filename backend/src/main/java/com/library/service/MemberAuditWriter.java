package com.library.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.dto.MemberDetail;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Audit must join the member transaction; it never logs private record contents. */
@Component
@RequiredArgsConstructor
public class MemberAuditWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public void save(Long id, Long operator, String reason, MemberDetail before, MemberDetail after) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("会员资料审计必须参与业务事务");
        try {
            jdbc.update("INSERT INTO member_profile_change(user_id,operator_id,change_source,change_reason,before_json,after_json) VALUES (?,?,'ADMIN_MEMBER',?,?,?)",
                    id, operator, reason, json.writeValueAsString(before), json.writeValueAsString(after));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("会员资料审计生成失败");
        }
    }
}
