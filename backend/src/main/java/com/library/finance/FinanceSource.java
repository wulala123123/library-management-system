package com.library.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import static com.library.finance.FinanceSupport.*;

/** Fixed SQL projection only. The complete protected archive never crosses JDBC. */
final class FinanceSource {
    static final List<String> KEYS=List.of("实际缴纳费用","实际缴纳押金","缴费状态","押金缴纳状态","押金");
    final JdbcTemplate jdbc; final boolean mysql;
    FinanceSource(JdbcTemplate jdbc,boolean mysql) { this.jdbc=jdbc;this.mysql=mysql; }
    String binary(String expression) { return "CAST("+expression+" AS "+(mysql?"BINARY":"VARBINARY")+")"; }
    String fn(String name,String args) { return (mysql?name:"FIN_"+name)+"("+args+")"; }
    JsonNode view(long member,String asOf) {
        var profiles=jdbc.query("SELECT source_key,source_system,source_archive_id,paid_fee,deposit_amount,deposit_status FROM member_profile WHERE user_id=?",
                (r,i)->new Profile(obj("profilePresent",true,"sourceKey",r.getString(1),"sourceSystem",r.getString(2),"sourceArchiveId",r.getObject(3)==null||r.getLong(3)<1?null:Long.toString(r.getLong(3)),
                        "legacyPaidFee",legacy(r.getBigDecimal(4)),"legacyDepositAmount",legacy(r.getBigDecimal(5)),"legacyDepositStatus",r.getString(6)),
                        obj("sourceArchiveId",r.getString(3),"paidFee",r.getString(4),"depositAmount",r.getString(5),"depositStatus",r.getString(6)),
                        r.getBigDecimal(4)!=null&&r.getBigDecimal(4).signum()<0||r.getBigDecimal(5)!=null&&r.getBigDecimal(5).signum()<0,r.getObject(3)!=null&&r.getLong(3)<1),member);
        ObjectNode profile=profiles.isEmpty()?obj("profilePresent",false,"sourceKey",null,"sourceSystem",null,"sourceArchiveId",null,"legacyPaidFee",null,"legacyDepositAmount",null,"legacyDepositStatus",null):profiles.get(0).value;
        String state="NO_PROFILE"; List<String> reasons=new ArrayList<>(); JsonNode archive=null; List<JsonNode> fields=unavailable(); String proof=null;
        if(!profiles.isEmpty()) {
            if(profiles.get(0).invalidAmount)reasons.add("LEGACY_PROFILE_AMOUNT_INVALID");
            String key=nullable(profile,"sourceKey"),system=nullable(profile,"sourceSystem"),archiveId=nullable(profile,"sourceArchiveId");
            if("manual".equals(system)) state="MANUAL";
            else if(profiles.get(0).invalidLink||key==null||!key.startsWith("huiben:")||key.length()==7||system!=null&&!"huiben".equals(system)) { state="UNVERIFIED_LINK";reasons.add("SOURCE_LINK_UNVERIFIED"); }
            else {
                String uid=key.substring(7);
                var rows=jdbc.query(projection()+" FROM legacy_record WHERE "+binary("source_system")+"=? AND "+binary("record_type")+"=? AND "+binary("source_key")+"=? ORDER BY id LIMIT 2",
                        (r,i)->project(r),bytes("huiben"),bytes("MEMBER"),bytes(uid));
                if(rows.size()!=1||rows.get(0).memberId!=member||archiveId!=null&&!archiveId.equals(rows.get(0).archive.path("archiveId").asText())) {
                    state="UNVERIFIED_LINK";reasons.add("SOURCE_LINK_UNVERIFIED");
                } else {
                    var row=rows.get(0); archive=row.archive;
                    if(!row.valid||!"OBJECT".equals(row.rootType)) {state="INVALID_ARCHIVE";reasons.add(row.valid?"ARCHIVE_ROOT_NOT_OBJECT":"ARCHIVE_INVALID_JSON");}
                    else {state="VERIFIED_LINK";fields=row.fields;proof=hash(obj("profile",profile,"legacyScalars",profiles.get(0).raw,"archive",archive,"fields",fields));}
                }
            }
        }
        if(!"VERIFIED_LINK".equals(state)) reasons.add("SOURCE_UNAVAILABLE");
        return obj("memberId",Long.toString(member),"sourceState",state,"reasonCodes",reasons,"profile",profile,"archive",archive,"fields",fields,"proofDigest",proof,"asOf",asOf);
    }
    record Profile(ObjectNode value,JsonNode raw,boolean invalidAmount,boolean invalidLink) {}
    static String nullable(JsonNode n,String key) { return n.path(key).isNull()?null:n.path(key).asText(); }
    static String legacy(BigDecimal v) { return v==null||v.signum()<0?null:money(v); }
    static List<JsonNode> unavailable() {
        return KEYS.stream().map(k->(JsonNode)obj("key",k,"presence","MISSING","jsonType","MISSING","reasonCode","SOURCE_UNAVAILABLE","rawText",null)).toList();
    }
    record Projected(long memberId,JsonNode archive,boolean valid,String rootType,List<JsonNode> fields) {}
    String projection() {
        String valid=fn("JSON_VALID","details_json"),root=fn("JSON_TYPE","details_json");
        String guard=valid+"=1";
        StringBuilder sql=new StringBuilder("SELECT id,user_id,source_key,"+fn("SHA2","details_json,256")+" AS full_hash,"+valid+" AS valid_json,CASE WHEN "+guard+" THEN "+root+" ELSE NULL END AS root_type");
        for(int i=0;i<KEYS.size();i++) {
            String path="'$.\""+KEYS.get(i)+"\"'",extract=fn("JSON_EXTRACT","details_json,"+path),type=fn("JSON_TYPE",extract),raw=fn("JSON_UNQUOTE",extract),length=fn("CHAR_LENGTH",raw);
            String safe="CASE WHEN "+guard+" THEN CASE WHEN "+root+"='OBJECT' THEN ";
            sql.append(',').append(safe).append(type).append(" ELSE NULL END ELSE NULL END AS t").append(i);
            sql.append(',').append(safe).append("CASE WHEN ").append(type).append(" IN ('STRING','INTEGER','DOUBLE','DECIMAL','NUMBER') AND ").append(length).append("<=").append(i<2?64:300).append(" THEN ").append(raw).append(" ELSE NULL END ELSE NULL END ELSE NULL END AS v").append(i);
            sql.append(',').append(safe).append("CASE WHEN ").append(type).append(" IN ('STRING','INTEGER','DOUBLE','DECIMAL','NUMBER') THEN ").append(length).append(" ELSE NULL END ELSE NULL END ELSE NULL END AS n").append(i);
        }
        return sql.toString();
    }
    Projected project(ResultSet r)throws SQLException {
        long member=r.getLong("user_id");
        JsonNode archive=obj("archiveId",Long.toString(r.getLong("id")),"sourceSystem","huiben","recordType","MEMBER","sourceKey",r.getString("source_key"),"memberId",Long.toString(member),"archiveHash",r.getString("full_hash").toLowerCase(Locale.ROOT));
        List<JsonNode> fields=new ArrayList<>();
        for(int i=0;i<KEYS.size();i++) {
            String type=r.getString("t"+i),raw=r.getString("v"+i),presence,reason;
            if(type==null) {type="MISSING";presence="MISSING";reason="SOURCE_FIELD_MISSING";}
            else if("NULL".equals(type)) {presence="NULL";reason="SOURCE_FIELD_NULL";raw=null;}
            else {
                if(Set.of("INTEGER","DOUBLE","DECIMAL").contains(type)) type="NUMBER";
                if(!"STRING".equals(type)&&!(i<2&&"NUMBER".equals(type))) {presence="INVALID_TYPE";reason="SOURCE_FIELD_INVALID_TYPE";raw=null;}
                else if(r.getLong("n"+i)>(i<2?64:300)) {presence="TOO_LONG";reason="SOURCE_FIELD_TOO_LONG";raw=null;}
                else if("".equals(raw)&&"STRING".equals(type)) {presence="EMPTY";reason="SOURCE_FIELD_EMPTY";}
                else {presence="VALUE";reason=i==4?"LEGACY_DEPOSIT_SEMANTICS_UNCONFIRMED":"SOURCE_UNVERIFIED_VALUE";}
            }
            fields.add(obj("key",KEYS.get(i),"presence",presence,"jsonType",type,"reasonCode",reason,"rawText",raw));
        }
        return new Projected(member,archive,r.getBoolean("valid_json"),r.getString("root_type"),fields);
    }
}
