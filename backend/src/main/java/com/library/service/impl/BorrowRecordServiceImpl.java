package com.library.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.library.common.PageResult;
import com.library.dto.BorrowRequest;
import com.library.entity.Book;
import com.library.entity.BorrowRecord;
import com.library.entity.User;
import com.library.mapper.BookMapper;
import com.library.mapper.BorrowRecordMapper;
import com.library.mapper.UserMapper;
import com.library.service.BorrowRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class BorrowRecordServiceImpl implements BorrowRecordService {

    private final BorrowRecordMapper borrowRecordMapper;
    private final BookMapper bookMapper;
    private final UserMapper userMapper;
    private final com.library.mapper.BookCopyMapper copyMapper;
    private final com.library.service.CopyInventoryProjection copyProjection;

    @Override
    public PageResult<BorrowRecord> getPage(int page, int size, String keyword, String status, Long userId) {
        Page<BorrowRecord> pageParam = new Page<>(page, size);
        IPage<BorrowRecord> result = borrowRecordMapper.selectPageWithDetail(pageParam, keyword, status, userId);
        return new PageResult<>(result.getRecords(), result.getTotal(), (long) page, (long) size);
    }

    @Override
    public BorrowRecord getById(Long id) {
        BorrowRecord record = borrowRecordMapper.selectById(id);
        if (record == null) {
            throw new RuntimeException("借阅记录不存在");
        }
        return record;
    }

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public BorrowRecord borrow(BorrowRequest request) {
        User user = userMapper.lockById(request.getUserId());
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        if (user.getStatus() == 0) {
            throw new RuntimeException("用户已被禁用，无法借阅");
        }

        Book book = bookMapper.lockById(request.getBookId());
        if (book == null) {
            throw new RuntimeException("图书不存在");
        }
        if (book.getStatus() == 0) {
            throw new RuntimeException("图书已下架，无法借阅");
        }
        if (book.getStockAvailable() <= 0) {
            throw new RuntimeException("图书库存不足，无法借阅");
        }

        Long activeCount = borrowRecordMapper.selectCount(
                new LambdaQueryWrapper<BorrowRecord>()
                        .eq(BorrowRecord::getUserId, request.getUserId())
                        .in(BorrowRecord::getStatus, "BORROWING", "OVERDUE")
        );
        if (activeCount >= 10) {
            throw new RuntimeException("借阅数量已达上限(10本)");
        }

        Long duplicateBorrow = borrowRecordMapper.selectCount(
                new LambdaQueryWrapper<BorrowRecord>()
                        .eq(BorrowRecord::getUserId, request.getUserId())
                        .eq(BorrowRecord::getBookId, request.getBookId())
                        .in(BorrowRecord::getStatus, "BORROWING", "OVERDUE")
        );
        if (duplicateBorrow > 0) {
            throw new RuntimeException("您已借阅该图书且尚未归还，不可重复借阅");
        }

        int borrowDays = request.getBorrowDays() != null ? request.getBorrowDays() : 30;
        if (borrowDays < 1 || borrowDays > 90) throw new RuntimeException("借阅天数必须为1-90天");

        // The book row is locked first for both borrowing and returns.
        com.library.service.CopyInventoryProjection.V2 registration=copyProjection.forBook(book.getId());
        if(request.getCopyId()==null&&(registration.hasUnknownStatus()||registration.hasLoanLinkAnomaly()))
            throw new RuntimeException("存在待核实状态或借阅关联，请明确选择已核实的可借实体册");
        com.library.entity.BookCopy copy = request.getCopyId() == null
                ? copyMapper.lockAvailable(book.getId()) : copyMapper.lockById(request.getCopyId());
        if (request.getCopyId() != null && (copy == null || !book.getId().equals(copy.getBookId()) || !"AVAILABLE".equals(copy.getStatus()) || copyMapper.activeLoans(copy.getId())!=0)) {
            throw new RuntimeException("所选馆内条码已借出或不属于该图书");
        }
        if (copy == null && (registration.inStockDifference()<=0 || registration.availableDifference()<=0)) {
            throw new RuntimeException("馆内条码状态与库存不一致，请先核对库存");
        }

        BorrowRecord record = new BorrowRecord();
        record.setUserId(request.getUserId());
        record.setBookId(request.getBookId());
        record.setCopyId(copy != null ? copy.getId() : null);
        record.setDueDateKind("DATED");
        record.setBorrowDate(LocalDate.now());
        record.setDueDate(LocalDate.now().plusDays(borrowDays));
        record.setRenewCount(0);
        record.setStatus("BORROWING");
        record.setRemark(request.getRemark());
        borrowRecordMapper.insert(record);

        if (copy != null) {
            if (copyMapper.changeCirculationState(copy.getId(),copy.getActionVersion(),"AVAILABLE","BORROWED") != 1) throw new RuntimeException("馆内条码状态或版本更新失败");
        }

        book.setStockAvailable(book.getStockAvailable() - 1);
        if (bookMapper.updateById(book) != 1) throw new RuntimeException("库存更新失败，请重试");

        return record;
    }

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public BorrowRecord returnBook(Long id) {
        BorrowRecord record = lockCirculationRecord(id);
        if (record == null) {
            throw new RuntimeException("借阅记录不存在");
        }
        if ("RETURNED".equals(record.getStatus())) return record;
        if (!java.util.Set.of("BORROWING", "OVERDUE").contains(record.getStatus())) {
            throw new RuntimeException("当前借阅状态不能归还");
        }

        record.setReturnDate(LocalDate.now());
        record.setStatus("RETURNED");
        if (borrowRecordMapper.updateById(record) != 1) throw new RuntimeException("借阅状态更新失败，请重试");

        Book book = bookMapper.lockById(record.getBookId());
        if (book == null) throw new RuntimeException("关联图书不存在，请联系管理员");
        if (record.getCopyId() != null) {
            var copy = copyMapper.lockById(record.getCopyId());
            if (copy == null || !book.getId().equals(copy.getBookId()) || !"BORROWED".equals(copy.getStatus())) {
                throw new RuntimeException("馆内条码状态不一致，请联系管理员");
            }
            if(copyMapper.activeLoans(copy.getId())!=0) throw new RuntimeException("同一实体册仍有其他在借关联，请核对");
            // This record is already RETURNED in the same transaction; all other active links must be absent.
            if (copyMapper.changeCirculationState(copy.getId(),copy.getActionVersion(),"BORROWED","AVAILABLE") != 1) throw new RuntimeException("馆内条码状态或版本更新失败");
        }
        book.setStockAvailable(book.getStockAvailable() + 1);
        if (bookMapper.updateById(book) != 1) throw new RuntimeException("库存更新失败，请重试");

        return record;
    }

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public BorrowRecord renew(Long id, Integer renewDays) {
        int days = renewDays != null ? renewDays : 30;
        if (days < 1 || days > 90) throw new RuntimeException("续借天数必须为1-90天");
        BorrowRecord record = lockCirculationRecord(id);
        if (record == null) {
            throw new RuntimeException("借阅记录不存在");
        }
        if ("RETURNED".equals(record.getStatus())) {
            throw new RuntimeException("该图书已归还，无法续借");
        }
        if (record.getRenewCount() >= 2) {
            throw new RuntimeException("续借次数已达上限(2次)");
        }

        if (record.getDueDate() == null) throw new RuntimeException("原记录未设置归还期限，无需续借");

        record.setDueDate(record.getDueDate().plusDays(days));
        record.setStatus(record.getDueDate().isBefore(LocalDate.now()) ? "OVERDUE" : "BORROWING");
        record.setRenewCount(record.getRenewCount() + 1);
        if (borrowRecordMapper.updateById(record) != 1) throw new RuntimeException("借阅状态更新失败，请重试");

        return record;
    }

    private BorrowRecord lockCirculationRecord(Long id) {
        BorrowRecord snapshot = borrowRecordMapper.selectById(id);
        if (snapshot == null) throw new RuntimeException("借阅记录不存在");
        userMapper.lockById(snapshot.getUserId());
        bookMapper.lockById(snapshot.getBookId());
        BorrowRecord record = borrowRecordMapper.lockById(id);
        if (record == null) throw new RuntimeException("借阅记录不存在");
        return record;
    }

    @Override
    public PageResult<BorrowRecord> getMyBorrows(int page, int size, Long userId, String status) {
        Page<BorrowRecord> pageParam = new Page<>(page, size);
        IPage<BorrowRecord> result = borrowRecordMapper.selectPageWithDetail(pageParam, null, status, userId);
        return new PageResult<>(result.getRecords(), result.getTotal(), (long) page, (long) size);
    }
}
