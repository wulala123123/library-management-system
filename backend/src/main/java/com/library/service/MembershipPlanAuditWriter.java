package com.library.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.dto.MembershipPlanResponses.Detail;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
public class MembershipPlanAuditWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public void save(Long id, Long operator, String action, String reason, Detail before, Detail after) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("套餐审计必须参与业务事务");
        try {
            jdbc.update("INSERT INTO membership_plan_change(plan_id,operator_id,action,change_reason,before_json,after_json) VALUES (?,?,?,?,?,?)",
                    id, operator, action, reason, json.writeValueAsString(before), json.writeValueAsString(after));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("套餐审计生成失败");
        }
    }
}
