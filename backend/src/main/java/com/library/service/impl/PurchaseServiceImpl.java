package com.library.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.library.common.PageResult;
import com.library.dto.ApprovalActionRequest;
import com.library.dto.PurchaseOrderRequest;
import com.library.entity.*;
import com.library.mapper.*;
import com.library.service.PurchaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class PurchaseServiceImpl implements PurchaseService {

    private final PurchaseOrderMapper orderMapper;
    private final PurchaseItemMapper itemMapper;
    private final ApprovalRecordMapper approvalMapper;
    private final BookCategoryMapper categoryMapper;
    private final DeptMapper deptMapper;
    private final UserMapper userMapper;

    @Override
    public PageResult<PurchaseOrder> getPage(int page, int size, String status, Long deptId) {
        Page<PurchaseOrder> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<PurchaseOrder> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) wrapper.eq(PurchaseOrder::getStatus, status);
        if (deptId != null) wrapper.eq(PurchaseOrder::getDeptId, deptId);
        wrapper.orderByDesc(PurchaseOrder::getCreateTime);
        IPage<PurchaseOrder> result = orderMapper.selectPage(pageParam, wrapper);
        result.getRecords().forEach(this::fillOrderInfo);
        return new PageResult<>(result.getRecords(), result.getTotal(), (long) page, (long) size);
    }

    @Override
    public PurchaseOrder getById(Long id) {
        PurchaseOrder order = orderMapper.lockById(id);
        if (order == null) throw new RuntimeException("采购单不存在");
        fillOrderInfo(order);
        List<PurchaseItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<PurchaseItem>().eq(PurchaseItem::getOrderId, id)
        );
        items.forEach(item -> {
            if (item.getCategoryId() != null) {
                BookCategory cat = categoryMapper.selectById(item.getCategoryId());
                if (cat != null) item.setCategoryName(cat.getName());
            }
        });
        order.setItems(items);
        return order;
    }

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public PurchaseOrder create(PurchaseOrderRequest request, Long applicantId) {
        PurchaseOrder order = new PurchaseOrder();
        order.setOrderNo(generateOrderNo());
        order.setDeptId(request.getDeptId());
        order.setTemplateId(request.getTemplateId());
        order.setApplicantId(applicantId);
        order.setStatus("DRAFT");
        order.setDeadline(request.getDeadline());
        order.setRemark(request.getRemark());
        orderMapper.insert(order);

        if (request.getItems() != null) {
            for (PurchaseOrderRequest.PurchaseItemRequest itemReq : request.getItems()) {
                PurchaseItem item = new PurchaseItem();
                BeanUtils.copyProperties(itemReq, item);
                item.setOrderId(order.getId());
                item.setInstockQuantity(0);
                itemMapper.insert(item);
            }
        }
        return order;
    }

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public PurchaseOrder update(PurchaseOrderRequest request) {
        if (request.getId() == null) throw new RuntimeException("采购单ID不能为空");
        PurchaseOrder order = orderMapper.lockById(request.getId());
        if (order == null) throw new RuntimeException("采购单不存在");
        if (!"DRAFT".equals(order.getStatus()) && !"REJECTED".equals(order.getStatus())) {
            throw new RuntimeException("只有草稿或已驳回的采购单可以修改");
        }
        order.setDeptId(request.getDeptId());
        order.setTemplateId(request.getTemplateId());
        order.setDeadline(request.getDeadline());
        order.setRemark(request.getRemark());
        orderMapper.updateById(order);

        itemMapper.delete(new LambdaQueryWrapper<PurchaseItem>().eq(PurchaseItem::getOrderId, order.getId()));
        if (request.getItems() != null) {
            for (PurchaseOrderRequest.PurchaseItemRequest itemReq : request.getItems()) {
                PurchaseItem item = new PurchaseItem();
                BeanUtils.copyProperties(itemReq, item);
                item.setOrderId(order.getId());
                item.setInstockQuantity(0);
                itemMapper.insert(item);
            }
        }
        return order;
    }

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void delete(Long id) {
        PurchaseOrder order = orderMapper.lockById(id);
        if (order == null) throw new RuntimeException("采购单不存在");
        if (!"DRAFT".equals(order.getStatus())) throw new RuntimeException("只有草稿状态可以删除");
        itemMapper.delete(new LambdaQueryWrapper<PurchaseItem>().eq(PurchaseItem::getOrderId, id));
        orderMapper.deleteById(id);
    }

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public PurchaseOrder submit(Long id) {
        PurchaseOrder order = orderMapper.lockById(id);
        if (order == null) throw new RuntimeException("采购单不存在");
        if (!"DRAFT".equals(order.getStatus()) && !"REJECTED".equals(order.getStatus())) {
            throw new RuntimeException("只有草稿或已驳回的采购单可以提交");
        }
        order.setStatus("PENDING");
        orderMapper.updateById(order);
        return order;
    }

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public PurchaseOrder approve(Long id, ApprovalActionRequest request, Long approverId) {
        PurchaseOrder order = orderMapper.lockById(id);
        if (order == null) throw new RuntimeException("采购单不存在");
        if (!"PENDING".equals(order.getStatus())) throw new RuntimeException("只有审批中的采购单可以审批");

        if (!java.util.Set.of("APPROVE", "REJECT").contains(request.getAction())) throw new RuntimeException("审批动作无效");
        User approver = userMapper.selectById(approverId);
        ApprovalRecord record = new ApprovalRecord();
        record.setOrderId(id);
        record.setNodeId(order.getCurrentNodeId() != null ? order.getCurrentNodeId() : "DEFAULT");
        record.setNodeName(order.getCurrentNodeId() != null ? null : "默认审批节点");
        record.setApproverId(approverId);
        record.setApproverName(approver != null ? approver.getRealName() : "");
        record.setAction(request.getAction());
        record.setComment(request.getComment());
        approvalMapper.insert(record);

        if ("REJECT".equals(request.getAction())) {
            order.setStatus("REJECTED");
        } else {
            order.setStatus("APPROVED");
        }
        orderMapper.updateById(order);
        return order;
    }

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void quickInstock(Long id) {
        throw com.library.service.IntakeCatalog.error(409, "INTAKE_PREVIEW_REQUIRED", "采购入库请先预览并确认实体册入库");
    }
    @Override
    public List<ApprovalRecord> getApprovalRecords(Long orderId) {
        return approvalMapper.selectList(
                new LambdaQueryWrapper<ApprovalRecord>()
                        .eq(ApprovalRecord::getOrderId, orderId)
                        .orderByAsc(ApprovalRecord::getCreateTime)
        );
    }

    private void fillOrderInfo(PurchaseOrder order) {
        if (order.getDeptId() != null) {
            Dept dept = deptMapper.selectById(order.getDeptId());
            if (dept != null) order.setDeptName(dept.getName());
        }
        if (order.getApplicantId() != null) {
            User user = userMapper.selectById(order.getApplicantId());
            if (user != null) order.setApplicantName(user.getRealName());
        }
    }

    private String generateOrderNo() {
        return "PO" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }
}
