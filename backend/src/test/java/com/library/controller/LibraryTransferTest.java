package com.library.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.library.dto.BorrowRequest;
import com.library.dto.UserRequest;
import com.library.entity.*;
import com.library.mapper.*;
import com.library.security.JwtTokenProvider;
import com.library.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LibraryTransferTest {
    @Autowired BookMapper books;
    @Autowired BookCopyMapper copies;
    @Autowired UserMapper users;
    @Autowired UserService userService;
    @Autowired BorrowRecordService circulation;
    @Autowired BorrowRecordMapper records;
    @Autowired BookService bookService;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenProvider tokens;
    @Autowired MockMvc mvc;

    User reader() {
        UserRequest request = new UserRequest();
        request.setUsername("transfer_" + UUID.randomUUID().toString().substring(0, 8));
        request.setPassword("DisposableReader123"); request.setRealName("迁移测试读者"); request.setRole("READER");
        return userService.create(request);
    }
    Book book() {
        Book book = new Book(); book.setIsbn(UUID.randomUUID().toString().substring(0, 20));
        book.setTitle("迁移测试图书"); book.setStockTotal(2); book.setStockAvailable(2); book.setStatus(1);
        books.insert(book); return book;
    }
    BookCopy copy(Book book, String state) {
        BookCopy copy = new BookCopy(); copy.setBookId(book.getId()); copy.setBarcode(UUID.randomUUID().toString()); copy.setStatus(state);
        copies.insert(copy); return copy;
    }
    BorrowRequest request(User user, Book book, BookCopy copy) {
        BorrowRequest request = new BorrowRequest(); request.setUserId(user.getId()); request.setBookId(book.getId());
        if (copy != null) request.setCopyId(copy.getId()); return request;
    }

    @Test void preservesExplicitCopyAndReturnsItsStockOnlyOnce() {
        Book book = book(); BookCopy first = copy(book, "AVAILABLE"), second = copy(book, "AVAILABLE");
        BorrowRecord loan = circulation.borrow(request(reader(), book, second));
        assertEquals(second.getId(), loan.getCopyId());
        assertEquals("AVAILABLE", copies.selectById(first.getId()).getStatus());
        assertEquals("BORROWED", copies.selectById(second.getId()).getStatus());
        assertEquals(1, books.selectById(book.getId()).getStockAvailable());
        circulation.returnBook(loan.getId()); circulation.returnBook(loan.getId());
        assertEquals("AVAILABLE", copies.selectById(second.getId()).getStatus());
        assertEquals(2, books.selectById(book.getId()).getStockAvailable());
    }

    @Test void rejectsWrongBookAndAlreadyBorrowedBarcodesWithoutChangingStock() {
        User user = reader(); Book book = book();
        BookCopy wrong = copy(book(), "AVAILABLE"), unavailable = copy(book, "BORROWED");
        assertThrows(RuntimeException.class, () -> circulation.borrow(request(user, book, wrong)));
        assertThrows(RuntimeException.class, () -> circulation.borrow(request(user, book, unavailable)));
        assertEquals(2, books.selectById(book.getId()).getStockAvailable());
        assertEquals(0, records.selectCount(new LambdaQueryWrapper<BorrowRecord>().eq(BorrowRecord::getUserId, user.getId())));
    }

    @Test void sourceUnlimitedLoanCanReturnButCannotCreateAnInventedDeadline() {
        Book book = book(); BookCopy copy = copy(book, "BORROWED");
        jdbc.update("UPDATE book SET stock_available = 1 WHERE id = ?", book.getId());
        BorrowRecord source = new BorrowRecord(); source.setUserId(reader().getId()); source.setBookId(book.getId());
        source.setCopyId(copy.getId()); source.setBorrowDate(LocalDate.now().minusMonths(2));
        source.setDueDateKind("UNLIMITED"); source.setStatus("BORROWING"); source.setRenewCount(0); records.insert(source);
        assertThrows(RuntimeException.class, () -> circulation.renew(source.getId(), 30));
        assertNull(records.selectById(source.getId()).getDueDate());
        circulation.returnBook(source.getId());
        assertEquals("RETURNED", records.selectById(source.getId()).getStatus());
        assertEquals(2, books.selectById(book.getId()).getStockAvailable());
    }

    @Test void barcodeSearchResolvesTheRightCatalogEntryAndAutoAllocationTracksACopy() {
        Book book = book(); BookCopy copy = copy(book, "AVAILABLE");
        var found = bookService.getPage(1, 20, copy.getBarcode(), null, null);
        assertEquals(1, found.getTotal()); assertEquals(book.getId(), found.getRecords().get(0).getId());
        BorrowRecord loan = circulation.borrow(request(reader(), book, null));
        assertEquals(copy.getId(), loan.getCopyId());
        var loans = circulation.getPage(1, 20, copy.getBarcode(), null, null);
        assertEquals(1, loans.getTotal()); assertEquals(copy.getBarcode(), loans.getRecords().get(0).getCopyBarcode());
    }

    String token(String name) {
        User user = users.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, name));
        return tokens.generateToken(user.getId(), user.getUsername(), user.getRole(), user.getTokenVersion());
    }

    @Test void financialAndOriginalMemberDataIsAdminOnly() throws Exception {
        for (String path : new String[]{"/legacy-data/members", "/legacy-data/summary", "/legacy-data/records?type=MEMBER"}) {
            mvc.perform(get(path).header("Authorization", "Bearer " + token("reader"))).andExpect(status().isForbidden());
            mvc.perform(get(path).header("Authorization", "Bearer " + token("librarian"))).andExpect(status().isForbidden());
            mvc.perform(get(path).header("Authorization", "Bearer " + token("admin"))).andExpect(status().isOk());
        }
    }
}
