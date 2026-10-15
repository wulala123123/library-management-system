package com.library.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
public class MemberEntitlementAuditWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public void save(Long member, Long operator, String action, String reason, Object before, Object after) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("权益审计必须参与会员事务");
        try {
            jdbc.update("INSERT INTO member_entitlement_change(user_id,operator_id,action,reason,before_json,after_json) VALUES (?,?,?,?,?,?)",
                    member, operator, action, reason, json.writeValueAsString(before), json.writeValueAsString(after));
        } catch (JsonProcessingException error) { throw new IllegalStateException("权益审计生成失败"); }
    }
}
