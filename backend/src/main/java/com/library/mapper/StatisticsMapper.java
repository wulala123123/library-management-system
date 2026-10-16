package com.library.mapper;

import com.library.dto.InventoryReconciliation;
import com.library.dto.StatisticsSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface StatisticsMapper {
    @Select("""
            SELECT COUNT(*) AS catalog_titles,
                   COALESCE(SUM(b.stock_total), 0) AS total_copies,
                   COALESCE(SUM(b.stock_available), 0) AS available_copies,
                   COALESCE(SUM(br.borrowed_copies), 0) AS borrowed_copies,
                   COALESCE(SUM(CASE WHEN b.stock_available > 0 THEN 1 ELSE 0 END), 0) AS available_titles,
                   COALESCE(SUM(CASE WHEN b.stock_available <= 2 THEN 1 ELSE 0 END), 0) AS low_stock_titles,
                   COALESCE(SUM(br.today_borrows), 0) AS today_borrows,
                   COALESCE(SUM(br.overdue_count), 0) AS overdue_count,
                   COALESCE(SUM(br.borrow_record_count), 0) AS borrow_record_count
            FROM book b
            LEFT JOIN (
                SELECT book_id, COUNT(*) AS borrow_record_count,
                       SUM(CASE WHEN status IN ('BORROWING', 'OVERDUE') THEN 1 ELSE 0 END) AS borrowed_copies,
                       SUM(CASE WHEN status = 'OVERDUE' THEN 1 ELSE 0 END) AS overdue_count,
                       SUM(CASE WHEN borrow_date = #{date} THEN 1 ELSE 0 END) AS today_borrows
                FROM borrow_record WHERE deleted = 0 GROUP BY book_id
            ) br ON br.book_id = b.id
            WHERE b.deleted = 0 AND b.status = 1
            """)
    StatisticsSummary selectCatalogSummary(@Param("date") LocalDate date);

    @Select("""
            SELECT COUNT(*) AS account_count,
                   COALESCE(SUM(CASE WHEN u.role = 'READER' THEN 1 ELSE 0 END), 0) AS member_count,
                   COALESCE(SUM(CASE WHEN u.role = 'READER' AND u.status = 1 THEN 1 ELSE 0 END), 0) AS enabled_reader_accounts,
                   COALESCE(SUM(CASE WHEN u.role = 'READER' AND u.status = 1
                       AND p.service_start <= #{date} AND p.service_end >= #{date}
                       THEN 1 ELSE 0 END), 0) AS valid_member_count,
                   COALESCE(SUM(CASE WHEN u.role = 'READER'
                       AND (p.user_id IS NULL OR p.service_start IS NULL OR p.service_end IS NULL)
                       THEN 1 ELSE 0 END), 0) AS unknown_validity_member_count
            FROM sys_user u LEFT JOIN member_profile p ON p.user_id = u.id
            WHERE u.deleted = 0
            """)
    StatisticsSummary selectMemberSummary(@Param("date") LocalDate date);

    @Select("""
            SELECT COUNT(*) FROM borrow_record br JOIN book b ON b.id = br.book_id
            WHERE br.deleted = 0 AND b.deleted = 0 AND b.status = 1 AND br.borrow_date = #{date}
            """)
    Long countBorrowsOnDate(@Param("date") LocalDate date);

    @Select("""
            SELECT b.id AS book_id, b.title,
                   b.stock_total AS total_copies, b.stock_available AS available_copies,
                   COALESCE(br.borrowed_copies, 0) AS borrowed_copies,
                   COALESCE(c.registered_copies, 0) AS registered_copies,
                   COALESCE(c.registered_available_copies, 0) AS registered_available_copies,
                   COALESCE(c.registered_borrowed_copies, 0) AS registered_borrowed_copies,
                   COALESCE(c.registered_other_copies, 0) AS registered_other_copies
            FROM book b
            LEFT JOIN (
                SELECT book_id, COUNT(*) AS registered_copies,
                       SUM(CASE WHEN CAST(status AS BINARY(256))=CAST('AVAILABLE' AS BINARY(256)) THEN 1 ELSE 0 END) AS registered_available_copies,
                       SUM(CASE WHEN CAST(status AS BINARY(256))=CAST('BORROWED' AS BINARY(256)) THEN 1 ELSE 0 END) AS registered_borrowed_copies,
                       SUM(CASE WHEN CAST(status AS BINARY(256))<>CAST('AVAILABLE' AS BINARY(256)) AND CAST(status AS BINARY(256))<>CAST('BORROWED' AS BINARY(256)) THEN 1 ELSE 0 END) AS registered_other_copies
                FROM book_copy GROUP BY book_id
            ) c ON c.book_id = b.id
            LEFT JOIN (
                SELECT book_id, COUNT(*) AS borrowed_copies FROM borrow_record
                WHERE deleted = 0 AND status IN ('BORROWING', 'OVERDUE') GROUP BY book_id
            ) br ON br.book_id = b.id
            WHERE b.deleted = 0 AND b.status = 1 ORDER BY b.id
            """)
    List<InventoryReconciliation.Item> selectReconciliation();
}
