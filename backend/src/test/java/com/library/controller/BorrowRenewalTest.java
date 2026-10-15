package com.library.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.library.dto.BorrowRequest;
import com.library.entity.Book;
import com.library.entity.BorrowRecord;
import com.library.entity.User;
import com.library.mapper.BookMapper;
import com.library.mapper.UserMapper;
import com.library.security.JwtTokenProvider;
import com.library.service.BorrowRecordService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BorrowRenewalTest {
    @Autowired MockMvc mvc;
    @Autowired BorrowRecordService borrows;
    @Autowired UserMapper users;
    @Autowired BookMapper books;
    @Autowired JwtTokenProvider tokens;

    private User account(String username) {
        return users.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
    }

    private BorrowRecord borrow() {
        Book book = books.selectOne(new LambdaQueryWrapper<Book>().eq(Book::getIsbn, "9787111698809"));
        BorrowRequest request = new BorrowRequest();
        request.setUserId(account("reader").getId());
        request.setBookId(book.getId());
        return borrows.borrow(request);
    }

    private ResultActions renew(BorrowRecord record, String body, String username) throws Exception {
        User user = account(username);
        String token = tokens.generateToken(user.getId(), user.getUsername(), user.getRole(), user.getTokenVersion());
        return mvc.perform(put("/borrows/" + record.getId() + "/renew")
                .header("Authorization", "Bearer " + token).contentType("application/json").content(body));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 90})
    void extendsOriginalDueDateByTheRequestedDays(int days) throws Exception {
        BorrowRecord record = borrow();
        int stock = books.selectById(record.getBookId()).getStockAvailable();
        renew(record, "{\"renewDays\":" + days + "}", "reader")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dueDate").value(record.getDueDate().plusDays(days).toString()))
                .andExpect(jsonPath("$.data.renewCount").value(1));
        assertEquals(record.getDueDate().plusDays(days), borrows.getById(record.getId()).getDueDate());
        assertEquals(stock, books.selectById(record.getBookId()).getStockAvailable());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{\"renewDays\":null}"})
    void olderRequestsStillDefaultToThirtyDays(String body) throws Exception {
        BorrowRecord record = borrow();
        renew(record, body, "admin").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dueDate").value(record.getDueDate().plusDays(30).toString()));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 91})
    void rejectsOutOfRangeDaysWithoutConsumingARenewal(int days) throws Exception {
        BorrowRecord record = borrow();
        renew(record, "{\"renewDays\":" + days + "}", "reader").andExpect(status().isBadRequest());
        assertThrows(RuntimeException.class, () -> borrows.renew(record.getId(), days));
        BorrowRecord unchanged = borrows.getById(record.getId());
        assertEquals(record.getDueDate(), unchanged.getDueDate());
        assertEquals(0, unchanged.getRenewCount());
    }

    @Test void customDaysStillRespectTheTwoRenewalLimit() throws Exception {
        BorrowRecord record = borrow();
        LocalDate original = record.getDueDate();
        renew(record, "{\"renewDays\":7}", "librarian").andExpect(status().isOk());
        renew(record, "{\"renewDays\":90}", "reader").andExpect(status().isOk());
        renew(record, "{\"renewDays\":1}", "admin").andExpect(status().isBadRequest());
        assertEquals(original.plusDays(97), borrows.getById(record.getId()).getDueDate());
        assertEquals(2, borrows.getById(record.getId()).getRenewCount());
    }

    @Test void returnedBooksCannotBeRenewed() throws Exception {
        BorrowRecord record = borrow();
        borrows.returnBook(record.getId());
        renew(record, "{\"renewDays\":7}", "reader").andExpect(status().isBadRequest());
        assertEquals(0, borrows.getById(record.getId()).getRenewCount());
    }
}
