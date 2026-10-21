package com.library.managementexport;

import com.fasterxml.jackson.databind.JsonNode;
import com.library.catalogattributes.CatalogBookQuery;
import com.library.common.PageResult;
import com.library.dto.MemberSummary;
import com.library.entity.Book;
import com.library.service.BookService;
import com.library.service.MemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.library.managementexport.ManagementCsv.*;

/** Reuses the actual list predicates. All pages participate in this one read snapshot. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class ManagementExportService {
    static final int MAX=20_000, PAGE_SIZE=100;
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final MemberService members;
    private final CatalogBookQuery catalog;
    private final BookService books;
    public record File(byte[] bytes,long total,String asOf,String filename) {}

    public File members(MultiValueMap<String,String> input) {
        validateKeys(input,Set.of("keyword","accountStatus","serviceStatus"),false);
        String keyword=keyword(input),service=one(input,"serviceStatus");
        Integer account=state(one(input,"accountStatus"));
        if(service!=null&&!service.isBlank()&&!Set.of("WAITING","ACTIVE","EXPIRED","UNKNOWN").contains(service))invalid();
        Snapshot snapshot=new Snapshot("members","会员资料",input,"人","全部当前筛选的未删除READER会员；账号启停与实际服务状态分别展示，不合并共享手机号会员");
        PageResult<MemberSummary> first=members.list(1,PAGE_SIZE,keyword,account,service);
        long total=first.getTotal();limit(total);
        List<MemberSummary> rows=new ArrayList<>();Set<Long> ids=new HashSet<>();long previous=Long.MAX_VALUE;
        for(int page=1;page<=Math.max(1,(total+PAGE_SIZE-1)/PAGE_SIZE);page++) {
            PageResult<MemberSummary> value=page==1?first:members.list(page,PAGE_SIZE,keyword,account,service);
            checkPage(total,value.getTotal(),value.getRecords().size(),page);
            for(MemberSummary row:value.getRecords()) {
                if(!snapshot.date.equals(row.getAsOfDate())||!ids.add(row.getId())||row.getId()>=previous)changed();
                previous=row.getId();rows.add(row);
            }
        }
        snapshot.finish(rows.size(),total);
        return snapshot.file(rows,List.of(column("会员ID",MemberSummary::getId),column("登录账号",MemberSummary::getUsername),
                column("姓名",MemberSummary::getRealName),column("手机号",MemberSummary::getPhone),column("邮箱",MemberSummary::getEmail),
                column("账号状态",MemberSummary::getAccountStatus),column("已登记会员档案",MemberSummary::isProfilePresent),
                column("会员卡号",MemberSummary::getCardNo),column("套餐名称",MemberSummary::getPlanName),column("服务开始日",MemberSummary::getServiceStart),
                column("服务结束日",MemberSummary::getServiceEnd),column("服务状态",MemberSummary::getServiceStatus),column("业务日期",MemberSummary::getAsOfDate),
                column("次数类型",MemberSummary::getUsageType),column("已用次数",MemberSummary::getUsedUses),column("总次数",MemberSummary::getTotalUses),
                column("剩余次数",MemberSummary::getRemainingUses),column("是否超用",MemberSummary::isOverused)));
    }

    public File books(MultiValueMap<String,String> input) {
        validateKeys(input,Set.of("keyword","categoryId","status","bindingCode","bindingState","ageLabels","ageState"),true);
        Snapshot snapshot=new Snapshot("books","书目资料",input,"种","全部当前/catalog-books筛选书目；登记书名与展示书名分列，库存为汇总册数，不含原档案");
        List<JsonNode> rows=new ArrayList<>();Set<Long> ids=new HashSet<>();long total=-1;
        for(int page=1;page==1||(long)(page-1)*PAGE_SIZE<total;page++) {
            MultiValueMap<String,String> query=new LinkedMultiValueMap<>();input.forEach((k,v)->query.put(k,new ArrayList<>(v)));
            query.set("page",Integer.toString(page));query.set("size",Integer.toString(PAGE_SIZE));
            JsonNode value=catalog.page(query);
            if(total<0){total=value.path("total").asLong();limit(total);}
            checkPage(total,value.path("total").asLong(),value.path("records").size(),page);
            for(JsonNode row:value.path("records")) {
                if(!ids.add(row.path("id").asLong()))changed();
                if(!rows.isEmpty()&&catalogOrder(rows.get(rows.size()-1),row)>0)changed();
                rows.add(row);
            }
        }
        snapshot.finish(rows.size(),total);
        String[] names={"书目ID","ISBN","登记书名","展示书名","已采用来源书名","作者","出版社","出版日期","分类ID","登记分类","定价","页数","封面","简介","登记库位","实体册总数（册）","可借册数（册）","书目状态","装帧代码","年龄标签"};
        String[] fields={"id","isbn","catalogTitle","displayTitle","adoptedSourceTitle","author","publisher","publishDate","categoryId","categoryName","price","pages","coverImage","description","location","stockTotal","stockAvailable","status","bindingCode","ageLabels"};
        List<Column<JsonNode>> columns=new ArrayList<>();for(int i=0;i<names.length;i++){String field=fields[i];columns.add(column(names[i],r->cell(r.get(field))));}
        return snapshot.file(rows,columns);
    }

    public File inventory(MultiValueMap<String,String> input) {
        validateKeys(input,Set.of("keyword","categoryId","status"),false);
        String keyword=keyword(input);Long category=positive(one(input,"categoryId"));Integer status=state(one(input,"status"));
        Snapshot snapshot=new Snapshot("inventory","库存列表",input,"种","与库存页getBookPage相同条件的全部未删除书目，默认包含下架书目；每行单位种，库存列单位册；汇总库存不等于已登记实体册台账");
        // BookService has no page-size clamp. One bounded fetch avoids the old mapper's
        // non-unique create_time ordering across pages; complete rows are then sorted.
        PageResult<Book> result=books.getPage(1,MAX+1,keyword,category,status);
        long total=result.getTotal();limit(total);
        List<Book> rows=new ArrayList<>(result.getRecords());Set<Long> ids=new HashSet<>();
        for(Book row:rows)if(!ids.add(row.getId()))changed();
        rows.sort(Comparator.comparing(Book::getCreateTime,Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Book::getId,Comparator.reverseOrder()));
        snapshot.finish(rows.size(),total);
        snapshot.metadata.put("排序","createTime DESC，书目ID DESC；旧列表同创建时刻的逐行顺序未作保证");
        return snapshot.file(rows,List.of(column("书目ID",Book::getId),column("登记书名",Book::getTitle),column("ISBN",Book::getIsbn),
                column("作者",Book::getAuthor),column("分类ID",Book::getCategoryId),column("登记分类",Book::getCategoryName),
                column("登记库位",Book::getLocation),column("实体册总数（册）",Book::getStockTotal),column("可借册数（册）",Book::getStockAvailable),column("书目状态",Book::getStatus)));
    }

    private static Object cell(JsonNode value) {
        if(value==null||value.isNull())return null;
        if(value.isArray()){List<String> labels=new ArrayList<>();value.forEach(v->labels.add(v.asText()));return String.join(" | ",labels);}
        return value.asText();
    }
    private static int catalogOrder(JsonNode a,JsonNode b) {
        Comparator<String> times=Comparator.nullsLast(Comparator.reverseOrder());
        int time=times.compare(a.path("createTime").isNull()?null:a.path("createTime").asText(),b.path("createTime").isNull()?null:b.path("createTime").asText());
        return time!=0?time:Long.compare(b.path("id").asLong(),a.path("id").asLong());
    }
    private static void checkPage(long total,long actual,int count,int page) {
        if(total!=actual||count!=Math.max(0,Math.min(PAGE_SIZE,total-(long)(page-1)*PAGE_SIZE)))changed();
    }
    private static void validateKeys(MultiValueMap<String,String> input,Set<String> allowed,boolean ages) {
        for(String key:input.keySet())if(!allowed.contains(key)||input.get(key)==null||input.get(key).isEmpty()
                ||(input.get(key).size()!=1&&!(ages&&key.equals("ageLabels"))))invalid();
    }
    private static String one(MultiValueMap<String,String> input,String name){return input.getFirst(name);}
    private static String keyword(MultiValueMap<String,String> input){String value=one(input,"keyword");if(value!=null&&value.codePointCount(0,value.length())>200)invalid();return value;}
    private static Integer state(String value){if(value==null)return null;if(!Set.of("0","1").contains(value))invalid();return Integer.valueOf(value);}
    private static Long positive(String value){if(value==null)return null;try{if(!value.matches("[0-9]+"))invalid();long id=Long.parseLong(value);if(id<1)invalid();return id;}catch(NumberFormatException error){invalid();return null;}}
    private static void invalid(){throw new ManagementExportException(400,"导出筛选参数无效，请勿传入分页、未知或重复参数");}
    private static void limit(long total){if(total<0)changed();if(total>MAX)throw new ManagementExportException(400,"当前筛选超过20000行，请缩小筛选范围后导出");}
    private static void changed(){throw new ManagementExportException(409,"导出读取数量、顺序或业务日期不一致，请重新查询后导出");}
    private static final class Snapshot {
        final String kind,asOf=Instant.now().toString();
        final LocalDate date=LocalDate.now(ZONE);
        final LinkedHashMap<String,Object> metadata=new LinkedHashMap<>();
        Snapshot(String kind,String title,MultiValueMap<String,String> filters,String unit,String scope) {
            this.kind=kind;metadata.put("导出类型",title);metadata.put("技术时点",asOf);metadata.put("业务日期（上海）",date);
            metadata.put("筛选条件",filters.isEmpty()?"全部":filters.toString());metadata.put("总量",0);metadata.put("单位",unit);metadata.put("统计口径",scope);
        }
        void finish(long actual,long total){if(actual!=total||!date.equals(LocalDate.now(ZONE)))changed();metadata.put("总量",total);}
        <T> File file(List<T> rows,List<Column<T>> columns){String stamp=DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.parse(asOf));return new File(ManagementCsv.render(metadata,rows,columns),rows.size(),asOf,"management-"+kind+"-"+stamp+".csv");}
    }
}
