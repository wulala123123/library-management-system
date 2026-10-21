package com.library.history;

/** Shared, set based origin evidence for the history drawer and staff inquiry.
 * No raw archive JSON is read. All source/barcode comparisons use complete bytes. */
public final class HistoryBorrowOriginSql {
    private final boolean mysql;
    public HistoryBorrowOriginSql(boolean mysql) { this.mysql = mysql; }
    public String binary(String value) { return "CAST(" + value + " AS " + (mysql ? "BINARY" : "VARBINARY") + ")"; }
    public String exact(String left, String right) { return binary(left) + "=" + binary(right); }

    public String cte() {
        String member = "u.id IS NOT NULL AND u.deleted=0 AND " + exact("u.role", "'READER'");
        String book = "b.id IS NOT NULL AND b.deleted=0";
        String copy = book + " AND c.id IS NOT NULL AND c.book_id=r.book_id";
        String counter = "e.item_order_id IS NOT NULL AND e.order_id=e.item_order_id AND e.order_member=e.user_id "
                + "AND e.item_book=e.book_id AND e.item_copy=e.copy_id AND " + exact("e.item_barcode", "e.valid_barcode")
                + " AND e.member_valid=1 AND e.copy_valid=1 AND e.source_key IS NULL";
        String ordinal = "SUBSTRING(e.source_key,CHAR_LENGTH(e.member_source_key)+2)";
        // Checking the suffix for any non digit also rejects trailing line breaks/spaces;
        // regexp end anchors differ across MySQL ICU and Java on those values.
        String imported = "e.member_valid=1 AND e.copy_valid=1 AND e.member_archive_id IS NOT NULL AND e.book_archive_id IS NOT NULL "
                + "AND (e.book_archive_user IS NULL OR e.book_archive_user=e.user_id) "
                + "AND LOCATE(':',SUBSTRING(e.member_source_key,8))=0 AND "
                + exact("e.source_key", "CONCAT(e.member_source_key,':'," + ordinal + ")")
                + " AND CHAR_LENGTH(" + ordinal + ")>0 AND SUBSTRING(" + ordinal + ",1,1) IN ('1','2','3','4','5','6','7','8','9') "
                + "AND NOT REGEXP_LIKE(" + ordinal + ",'[^0-9]')";
        return "WITH history_member_counts AS (SELECT user_id,COUNT(*) AS member_count FROM legacy_record "
                + "WHERE user_id IS NOT NULL AND " + exact("record_type", "'MEMBER'") + " GROUP BY user_id), "
                + "history_imported_members AS (SELECT p.user_id,p.source_key,ma.id AS archive_id FROM member_profile p "
                + "JOIN history_member_counts mc ON mc.user_id=p.user_id AND mc.member_count=1 "
                + "JOIN legacy_record ma ON ma.user_id=p.user_id AND ma.book_id IS NULL AND "
                + exact("ma.source_system", "'huiben'") + " AND " + exact("ma.record_type", "'MEMBER'") + " AND "
                + exact("p.source_key", "CONCAT('huiben:',ma.source_key)")
                + " WHERE CHAR_LENGTH(ma.source_key)>0 AND (p.source_system IS NULL OR " + exact("p.source_system", "'huiben'")
                + ") AND (p.source_archive_id IS NULL OR p.source_archive_id=ma.id)), "
                + "borrow_evidence AS (SELECT r.*,u.real_name AS member_name,b.title AS current_book_title,b.isbn AS current_book_isbn,"
                + "b.cover_image AS current_book_cover,CASE WHEN " + member + " THEN 1 ELSE 0 END AS member_valid,"
                + "CASE WHEN " + book + " THEN 1 ELSE 0 END AS book_valid,CASE WHEN " + copy + " THEN 1 ELSE 0 END AS copy_valid,"
                + "CASE WHEN " + copy + " THEN c.barcode ELSE NULL END AS valid_barcode,"
                + "im.source_key AS member_source_key,im.archive_id AS member_archive_id,ba.id AS book_archive_id,ba.user_id AS book_archive_user,"
                + "i.order_id AS item_order_id,i.book_id AS item_book,i.copy_id AS item_copy,i.barcode AS item_barcode,"
                + "o.id AS order_id,o.user_id AS order_member FROM borrow_record r "
                + "LEFT JOIN sys_user u ON u.id=r.user_id LEFT JOIN book b ON b.id=r.book_id LEFT JOIN book_copy c ON c.id=r.copy_id "
                + "LEFT JOIN history_imported_members im ON im.user_id=r.user_id "
                + "LEFT JOIN legacy_record ba ON ba.book_id=r.book_id AND " + exact("ba.source_system", "'huiben'")
                + " AND " + exact("ba.record_type", "'BOOK'") + " AND " + binary("ba.source_key") + "=c.barcode_key "
                + "LEFT JOIN circulation_order_item i ON i.record_id=r.id LEFT JOIN circulation_order o ON o.id=i.order_id WHERE r.deleted=0), "
                + "borrow_classified AS (SELECT e.*,CASE WHEN e.item_order_id IS NOT NULL THEN CASE WHEN " + counter
                + " THEN 'COUNTER_ORDER' ELSE 'UNVERIFIED' END WHEN e.source_key IS NULL AND e.member_valid=1 AND e.book_valid=1 "
                + "AND (e.copy_id IS NULL OR e.copy_valid=1) THEN 'SYSTEM' WHEN " + imported
                + " THEN 'IMPORTED' ELSE 'UNVERIFIED' END AS origin,CASE WHEN " + counter
                + " THEN e.item_order_id ELSE NULL END AS counter_order_id FROM borrow_evidence e) ";
    }
}
