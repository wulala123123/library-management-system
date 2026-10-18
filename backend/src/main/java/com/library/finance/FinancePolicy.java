package com.library.finance;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import static com.library.finance.FinanceSupport.*;

/** Deployment policy is deliberately unconfirmed. It is not an administrative editing API. */
public final class FinancePolicy {
    private FinancePolicy() {}
    static JsonNode current() {
        var p=obj("currency","CNY","scale",2,"rounding","REJECT_EXTRA_SCALE","businessAmountLimit",null,
                "paymentMethodsState","UNCONFIRMED","paymentMethodCodes",List.of(),"datePolicyState","UNCONFIRMED",
                "cutoffAt",null,"financialTimeZone",null,"backdateRuleRef",null,"debtPolicyState","UNCONFIRMED",
                "debtPolicyVersion",null,"enabledActions",List.of());
        p.put("digest",hash(p)); return p;
    }
}
