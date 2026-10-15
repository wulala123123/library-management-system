package com.library.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.library.dto.BorrowRequest;
import com.library.dto.UserRequest;
import com.library.entity.Book;
import com.library.entity.BorrowRecord;
import com.library.entity.User;
import com.library.mapper.BookMapper;
import com.library.mapper.UserMapper;
import com.library.security.JwtTokenProvider;
import com.library.service.BorrowRecordService;
import com.library.service.UserService;
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
import java.util.UUID;

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
    @Autowired UserService userService;
    @Autowired UserMapper users;
    @Autowired BookMapper books;
    @Autowired JwtTokenProvider tokens;
    private String readerUsername;

    private User account(String username) {
        return users.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
    }

    private BorrowRecord borrow() {
        UserRequest userRequest = new UserRequest();
        readerUsername = "renew_" + UUID.randomUUID().toString().substring(0, 8);
        userRequest.setUsername(readerUsername);
        userRequest.setPassword("RenewReaderPassword123");
        userRequest.setRealName("续借测试读者");
        userRequest.setRole("READER");
        User reader = userService.create(userRequest);
        Book book = new Book();
        book.setIsbn(UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        book.setTitle("续借测试图书");
        book.setStockTotal(1);
        book.setStockAvailable(1);
        book.setStatus(1);
        books.insert(book);
        BorrowRequest request = new BorrowRequest();
        request.setUserId(reader.getId());
        request.setBookId(book.getId());
        return borrows.borrow(request);
    }

    private ResultActions renew(BorrowRecord record, String body, String username) throws Exception {
        User user = account("reader".equals(username) ? readerUsername : username);
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
