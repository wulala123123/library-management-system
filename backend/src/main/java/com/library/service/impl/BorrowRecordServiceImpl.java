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

        BorrowRecord record = new BorrowRecord();
        record.setUserId(request.getUserId());
        record.setBookId(request.getBookId());
        record.setBorrowDate(LocalDate.now());
        record.setDueDate(LocalDate.now().plusDays(borrowDays));
        record.setRenewCount(0);
        record.setStatus("BORROWING");
        record.setRemark(request.getRemark());
        borrowRecordMapper.insert(record);

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
        book.setStockAvailable(book.getStockAvailable() + 1);
        if (bookMapper.updateById(book) != 1) throw new RuntimeException("库存更新失败，请重试");

        return record;
    }

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public BorrowRecord renew(Long id) {
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

        record.setDueDate(record.getDueDate().plusDays(30));
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
