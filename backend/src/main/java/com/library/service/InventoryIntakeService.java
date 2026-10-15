package com.library.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.dto.InventoryIntakeContracts.*;
import com.library.entity.Book;
import com.library.mapper.BookMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.library.service.IntakeCatalog.*;

/** Persistent preview and exactly one atomic stock/copy/source transaction per request. */
@Service
@RequiredArgsConstructor
public class InventoryIntakeService {
    private static final String PREFIX = "LMS-C-";
    private final JdbcTemplate jdbc;
    private final BookMapper books;
    private final IntakeCatalog catalog;
    private final ObjectMapper json;

    public record Item(Long id, String isbn, String title, String author, String publisher, Long categoryId,
                       java.math.BigDecimal price, int quantity, int instockQuantity) {}
    public record PurchaseGuard(Long id, String orderNo, String status, List<Item> items) {}
    public record Plan(Source source, List<PreviewLine> lines, List<BookSnapshot> books, List<Progress> purchaseProgress,
                       List<String> warnings, PurchaseGuard purchaseGuard) {}
    private record Row(Long id, String requestHash, String digest, Long owner, String state, Instant expiresAt,
                       String requestJson, String planJson, String receiptJson) {}
    private record Resolved(LineInput input, Long bookId, Metadata newBook, Long itemId) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public View preview(PreviewRequest request, Long actor) {
        validate(request);
        books.lockCatalog();
        Row old = rowByKey(request.getIdempotencyKey(), true);
        String hash = catalog.digest(request);
        if (old != null) {
            owner(old, actor);
            if (!old.requestHash().equals(hash)) throw error(409, "IDEMPOTENCY_KEY_REUSED", "该办理键已用于其他输入，请保留原单并重新预览");
            return view(old);
        }
        Plan plan = build(request, null);
        String digest = catalog.digest(plan);
        Instant expiry = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(15, ChronoUnit.MINUTES);
        Long id = insert("INSERT INTO inventory_intake_request(idempotency_key,request_hash,preview_digest,source_type,purchase_order_id,operator_id,request_json,preview_json,state,expires_at) VALUES (?,?,?,?,?,?,?,?,'PREVIEWED',?)",
                bytes(request.getIdempotencyKey()), hash, digest, plan.source().type(), plan.source().purchaseOrderId(), actor,
                write(request), write(plan), LocalDateTime.ofInstant(expiry, ZoneOffset.UTC));
        return new View(id, "PREVIEWED", digest, expiry.toString(), plan.source(), plan.lines(), plan.books(), plan.purchaseProgress(), plan.warnings(), null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt confirm(Long id, ConfirmRequest request, Long actor) {
        if (id == null || id <= 0 || request == null) invalid();
        String key = key(request.getIdempotencyKey());
        if (request.getPreviewDigest() == null || !request.getPreviewDigest().matches("[a-f0-9]{64}")) invalid();
        books.lockCatalog();
        Row row = rowById(id, true); owner(row, actor);
        Row keyed = rowByKey(key, false);
        if (keyed == null || !keyed.id().equals(id) || !row.digest().equals(request.getPreviewDigest()))
            throw error(409, "IDEMPOTENCY_KEY_REUSED", "办理键或预览摘要不一致，请查询原办理结果");
        // Replay precedes expiry, source status, current stock, and barcode checks.
        if (row.state().equals("COMMITTED")) return read(row.receiptJson(), Receipt.class);
        if (!Instant.now().isBefore(row.expiresAt())) throw new InventoryIntakeException(409, "PREVIEW_EXPIRED", "预览已过期，请核对后重新预览", id);
        PreviewRequest original = read(row.requestJson(), PreviewRequest.class);
        Plan saved = read(row.planJson(), Plan.class);
        Plan current;
        try { current = build(original, saved.lines()); }
        catch (InventoryIntakeException e) {
            if (Set.of("CATALOG_CONFLICT", "BOOK_NOT_FOUND").contains(e.reasonCode()))
                throw new InventoryIntakeException(409, "PREVIEW_STALE", "书目已变化，请重新预览", id);
            throw e;
        }
        if (!row.digest().equals(catalog.digest(current))) throw new InventoryIntakeException(409, "PREVIEW_STALE", "库存、书目或采购明细已变化，请重新预览", id);

        Map<String, Long> resolvedNew = new HashMap<>();
        Map<Long, BookSnapshot> before = new TreeMap<>();
        for (BookSnapshot snapshot : current.books()) {
            Long bookId = snapshot.bookId();
            if (bookId == null) {
                Metadata metadata = current.lines().stream().filter(l -> l.newBook() != null && l.newBook().isbn().equals(snapshot.isbn())).findFirst().orElseThrow().newBook();
                bookId = catalog.insert(metadata); resolvedNew.put(snapshot.isbn(), bookId);
            }
            before.put(bookId, snapshot);
        }
        Map<Long, Integer> added = new TreeMap<>();
        List<CopyReceipt> copies = new ArrayList<>(); List<FlowReceipt> flows = new ArrayList<>();
        Map<Long, Integer> itemAdded = new TreeMap<>();
        for (PreviewLine line : current.lines()) {
            Long bookId = line.bookId() != null ? line.bookId() : resolvedNew.get(line.newBook().isbn());
            int quantity = line.copies().size(); added.merge(bookId, quantity, Math::addExact);
            Long flowId = insert("INSERT INTO stock_flow(book_id,flow_type,quantity,related_id,operator_id,remark) VALUES (?,'IN',?,?,?,?)",
                    bookId, quantity, current.source().purchaseOrderId(), actor, original.getReason());
            flows.add(new FlowReceipt(flowId, bookId, line.lineKey(), line.purchaseItemId(), quantity));
            if (line.purchaseItemId() != null) itemAdded.merge(line.purchaseItemId(), quantity, Math::addExact);
            for (PreviewCopy copy : line.copies()) {
                Long copyId;
                try { copyId = insert("INSERT INTO book_copy(book_id,barcode,status,location) VALUES (?,?,'AVAILABLE',?)", bookId, copy.barcode(), copy.location()); }
                catch (org.springframework.dao.DuplicateKeyException e) { throw new InventoryIntakeException(409, "BARCODE_CONFLICT", "馆内条码已被占用，请重新核对", id); }
                insert("INSERT INTO inventory_intake_copy(intake_id,line_key,copy_ordinal,book_id,copy_id,purchase_item_id,stock_flow_id,barcode_input) VALUES (?,?,?,?,?,?,?,?)",
                        id, bytes(line.lineKey()), copy.ordinal(), bookId, copyId, line.purchaseItemId(), flowId, copy.input());
                copies.add(new CopyReceipt(copyId, bookId, line.lineKey(), copy.ordinal(), copy.barcode(), copy.input(), copy.location(), "AVAILABLE", flowId, line.purchaseItemId()));
            }
        }
        List<BookReceipt> bookReceipts = new ArrayList<>();
        for (var entry : added.entrySet()) {
            long bookId = entry.getKey(); int n = entry.getValue(); BookSnapshot b = before.get(bookId);
            int total, available;
            try {
                total = Math.addExact(b.stockTotal(), n);
                available = Math.addExact(b.stockAvailable(), n);
            } catch (ArithmeticException e) {
                throw new InventoryIntakeException(409, "PREVIEW_STALE", "库存数量超出可保存范围，请核对后重新预览", id);
            }
            if (total < 0 || available < 0 || available > total) throw error(409, "PREVIEW_STALE", "库存口径异常，请核对");
            if (jdbc.update("UPDATE book SET stock_total=stock_total+?,stock_available=stock_available+?,update_time=CURRENT_TIMESTAMP WHERE id=? AND deleted=0 AND stock_total=? AND stock_available=?",
                    n, n, bookId, b.stockTotal(), b.stockAvailable()) != 1) throw error(409, "PREVIEW_STALE", "库存已变化，请重新预览");
            bookReceipts.add(new BookReceipt(bookId, b.stockTotal(), b.stockAvailable(), total, available, n));
        }
        List<Progress> progress = new ArrayList<>();
        for (Progress p : current.purchaseProgress()) {
            int n = itemAdded.getOrDefault(p.purchaseItemId(), 0), after = p.instockQuantity() + n;
            if (jdbc.update("UPDATE purchase_item SET instock_quantity=instock_quantity+?,update_time=CURRENT_TIMESTAMP WHERE id=? AND deleted=0 AND instock_quantity=? AND quantity>=instock_quantity+?",
                    n, p.purchaseItemId(), p.instockQuantity(), n) != 1) throw error(409, "PREVIEW_STALE", "采购明细已变化，请重新预览");
            progress.add(new Progress(p.purchaseItemId(), p.quantity(), after, p.quantity() - after, n));
        }
        if (current.source().purchaseOrderId() != null) {
            long pending = jdbc.queryForObject("SELECT COUNT(*) FROM purchase_item WHERE order_id=? AND deleted=0 AND quantity>instock_quantity", Long.class, current.source().purchaseOrderId());
            if (pending == 0) jdbc.update("UPDATE purchase_order SET status='COMPLETED',update_time=CURRENT_TIMESTAMP WHERE id=? AND status='APPROVED' AND deleted=0", current.source().purchaseOrderId());
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Receipt receipt = new Receipt(id, current.source(), actor, now.toString(), bookReceipts, copies, flows, progress);
        if (jdbc.update("UPDATE inventory_intake_request SET state='COMMITTED',confirmed_at=?,receipt_json=? WHERE id=? AND state='PREVIEWED'",
                LocalDateTime.ofInstant(now, ZoneOffset.UTC), write(receipt), id) != 1) throw error(500, "INTAKE_SAVE_FAILED", "入库保存失败，请查询办理结果");
        return receipt;
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public View get(Long id, Long actor) { if (id == null || id <= 0) invalid(); Row row = rowById(id, false); owner(row, actor); return view(row); }
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public View byKey(String value, Long actor) { Row row = rowByKey(key(value), false); owner(row, actor); return view(row); }

    private Plan build(PreviewRequest request, List<PreviewLine> fixed) {
        Source source = new Source(request.getSource().getType(), request.getSource().getPurchaseOrderId());
        PurchaseGuard guard = source.type().equals("PURCHASE") ? purchase(source.purchaseOrderId()) : null;
        Map<Long, Item> items = new TreeMap<>(); if (guard != null) for (Item i : guard.items()) items.put(i.id(), i);
        Map<Long, Integer> itemQuantities = new TreeMap<>();
        List<Resolved> targets = new ArrayList<>(); List<Metadata> pendingMetadata = new ArrayList<>(); Set<Long> existingIds = new TreeSet<>();
        for (LineInput line : request.getLines()) {
            TargetInput t = line.getTarget(); Long bookId; Metadata newBook = null;
            if (guard == null) {
                bookId = t.getBookId();
                if (bookId == null) {
                    newBook = catalog.newMetadata(t.getNewBook());
                    if (catalog.byIsbn(newBook.isbn()) != null) throw error(409, "CATALOG_CONFLICT", "ISBN已存在，请明确选择已有书目");
                }
            } else {
                Item item = items.get(t.getPurchaseItemId()); if (item == null) invalid();
                int receiving = itemQuantities.merge(item.id(), line.getCopies().size(), Math::addExact);
                if (receiving > item.quantity() - item.instockQuantity()) throw error(409, "QUANTITY_EXCEEDS_PENDING", "本次册数超过采购待入库数量");
                String isbn = item.isbn() != null && !item.isbn().isBlank() ? item.isbn().trim() : "PO-" + guard.id() + "-" + item.id();
                if (isbn.codePointCount(0, isbn.length()) > 20) throw error(409, "INTERNAL_CATALOG_KEY_TOO_LONG", "采购内部书目编号超长，请先核对ISBN");
                Book b = catalog.byIsbn(isbn); bookId = b == null ? null : b.getId();
                if (b != null && (Integer.valueOf(1).equals(b.getDeleted()) || !matching(catalog.metadata(b), item)))
                    throw error(409, "CATALOG_CONFLICT", "ISBN对应书名、作者或出版社不一致，或书目已删除，请核对");
                if (b == null) newBook = new Metadata(isbn, item.title(), item.author(), item.publisher(), null, item.categoryId(), item.price(), null, null, null, null, 1);
            }
            if (bookId != null) existingIds.add(bookId);
            if (newBook != null) {
                Metadata same = null;
                for (Metadata pending : pendingMetadata) if (catalog.sameIsbn(pending.isbn(), newBook.isbn())) { same = pending; break; }
                if (same != null) {
                    if (guard == null ? !same.equals(newBook) : !matching(same, items.get(t.getPurchaseItemId()))) throw error(409, "CATALOG_CONFLICT", "本次相同ISBN的资料不一致，请核对");
                    newBook = same;
                } else pendingMetadata.add(newBook);
            }
            targets.add(new Resolved(line, bookId, newBook, t.getPurchaseItemId()));
        }
        Map<Long, Book> locked = new TreeMap<>(); List<BookSnapshot> snapshots = new ArrayList<>(); List<String> warnings = new ArrayList<>();
        for (Long id : existingIds) {
            Book b = books.lockById(id); if (b == null) throw error(404, "BOOK_NOT_FOUND", "图书不存在");
            locked.put(id, b);
            long count = jdbc.queryForObject("SELECT COUNT(*) FROM book_copy WHERE book_id=?", Long.class, id);
            long available = jdbc.queryForObject("SELECT COUNT(*) FROM book_copy WHERE book_id=? AND status='AVAILABLE'", Long.class, id);
            long diff = b.getStockTotal() - count, availableDiff = b.getStockAvailable() - available;
            snapshots.add(new BookSnapshot(id, b.getIsbn(), b.getTitle(), catalog.digest(catalog.metadata(b)), b.getStockTotal(), b.getStockAvailable(), count, available, diff, availableDiff, false));
            if ((diff != 0 || availableDiff != 0) && warnings.isEmpty()) warnings.add("HISTORICAL_STOCK_DIFFERENCE");
        }
        for (Metadata m : pendingMetadata) snapshots.add(new BookSnapshot(null, m.isbn(), m.title(), catalog.digest(m), 0, 0, 0, 0, 0, 0, true));
        List<PreviewLine> lines = new ArrayList<>(); Set<String> barcodes = new HashSet<>();
        for (int l = 0; l < targets.size(); l++) {
            Resolved t = targets.get(l); List<PreviewCopy> copies = new ArrayList<>();
            if (guard != null && t.bookId() != null && !matching(catalog.metadata(locked.get(t.bookId())), items.get(t.itemId())))
                throw error(409, "CATALOG_CONFLICT", "采购书目资料已变化，请核对");
            for (int c = 0; c < t.input().getCopies().size(); c++) {
                CopyInput input = t.input().getCopies().get(c); String barcode = input.getBarcode(); String kind = input.getInput().equals("GENERATE") ? "GENERATED" : "SCANNED";
                if (fixed != null) barcode = fixed.get(l).copies().get(c).barcode();
                else if (kind.equals("GENERATED")) {
                    int attempts = 0;
                    do { if (++attempts > 32) throw error(500, "INTAKE_SAVE_FAILED", "馆内码生成失败，请稍后重试"); barcode = PREFIX + UUID.randomUUID().toString().replace("-", ""); }
                    while (barcodes.contains(barcode) || barcodeExists(barcode));
                }
                if (!barcodes.add(barcode) || barcodeExists(barcode)) throw error(409, "BARCODE_CONFLICT", "馆内条码重复或已被占用，请核对");
                copies.add(new PreviewCopy(c + 1, kind, barcode, input.getLocation()));
            }
            lines.add(new PreviewLine(t.input().getLineKey(), t.bookId(), t.newBook(), t.itemId(), copies));
        }
        List<Progress> progress = new ArrayList<>();
        itemQuantities.forEach((id, qty) -> { Item item = items.get(id); progress.add(new Progress(id, item.quantity(), item.instockQuantity(), item.quantity() - item.instockQuantity(), qty)); });
        return new Plan(source, lines, snapshots, progress, warnings, guard);
    }
    private boolean matching(Metadata m, Item item) {
        return m.title() != null && item.title() != null && m.title().trim().equals(item.title().trim())
                && (item.author() == null || item.author().isBlank() || Objects.equals(m.author(), item.author()))
                && (item.publisher() == null || item.publisher().isBlank() || Objects.equals(m.publisher(), item.publisher()));
    }
    private PurchaseGuard purchase(Long id) {
        List<PurchaseGuard> orders = jdbc.query("SELECT id,order_no,status FROM purchase_order WHERE id=? AND deleted=0 FOR UPDATE", (rs, n) -> new PurchaseGuard(rs.getLong(1), rs.getString(2), rs.getString(3), null), id);
        if (orders.isEmpty()) throw error(404, "PURCHASE_NOT_FOUND", "采购单不存在");
        PurchaseGuard order = orders.get(0);
        if (!order.status().equals("APPROVED")) throw error(409, "PURCHASE_NOT_APPROVED", "采购单须审批通过且仍有待入库数量");
        List<Item> items = jdbc.query("SELECT id,isbn,book_title,author,publisher,category_id,price,quantity,instock_quantity FROM purchase_item WHERE order_id=? AND deleted=0 ORDER BY id FOR UPDATE",
                (rs, n) -> new Item(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getObject(6, Long.class), rs.getBigDecimal(7), rs.getInt(8), rs.getInt(9)), id);
        return new PurchaseGuard(id, order.orderNo(), order.status(), items);
    }
    private boolean barcodeExists(String barcode) { return jdbc.queryForObject("SELECT COUNT(*) FROM book_copy WHERE barcode_key=?", Long.class, bytes(barcode)) != 0; }
    private void validate(PreviewRequest r) {
        if (r == null || r.getSource() == null || r.getLines() == null || r.getLines().isEmpty() || r.getLines().size() > 1000) invalid();
        r.setIdempotencyKey(key(r.getIdempotencyKey())); text(r.getReason(), 500, true);
        String source = r.getSource().getType();
        if (!Set.of("MANUAL", "PURCHASE").contains(source == null ? "" : source)) invalid();
        boolean manual = source.equals("MANUAL");
        if (manual ? r.getSource().getPurchaseOrderId() != null : r.getSource().getPurchaseOrderId() == null || r.getSource().getPurchaseOrderId() <= 0) invalid();
        Set<String> lines = new HashSet<>(); int count = 0;
        for (LineInput line : r.getLines()) {
            if (line == null) invalid(); text(line.getLineKey(), 128, true);
            if (bytes(line.getLineKey()).length > 128 || !lines.add(line.getLineKey()) || line.getTarget() == null || line.getCopies() == null || line.getCopies().isEmpty()) invalid();
            count = Math.addExact(count, line.getCopies().size()); if (count > 1000) invalid();
            TargetInput t = line.getTarget(); boolean hasNew = t.getNewBook() != null && !t.getNewBook().isNull();
            if (manual ? (t.getPurchaseItemId() != null || (t.getBookId() != null) == hasNew || t.getBookId() != null && t.getBookId() <= 0)
                    : (t.getPurchaseItemId() == null || t.getPurchaseItemId() <= 0 || t.getBookId() != null || hasNew)) invalid();
            for (CopyInput copy : line.getCopies()) {
                if (copy == null || copy.getInput() == null || !Set.of("SCANNED", "GENERATE").contains(copy.getInput())) invalid();
                text(copy.getLocation(), 200, false);
                if (copy.getInput().equals("GENERATE")) { if (copy.getBarcode() != null) invalid(); }
                else { text(copy.getBarcode(), 64, true); if (bytes(copy.getBarcode()).length > 256 || copy.getBarcode().startsWith(PREFIX)) invalid(); }
            }
        }
    }
    private String key(String key) { if (key == null || !key.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) invalid(); return key.toLowerCase(Locale.ROOT); }
    private Row rowById(Long id, boolean lock) { List<Row> rows = jdbc.query("SELECT * FROM inventory_intake_request WHERE id=?" + (lock ? " FOR UPDATE" : ""), this::row, id); return rows.isEmpty() ? null : rows.get(0); }
    private Row rowByKey(String key, boolean lock) { List<Row> rows = jdbc.query("SELECT * FROM inventory_intake_request WHERE idempotency_key=?" + (lock ? " FOR UPDATE" : ""), this::row, bytes(key)); return rows.isEmpty() ? null : rows.get(0); }
    private Row row(ResultSet r, int n) throws SQLException { return new Row(r.getLong("id"), r.getString("request_hash"), r.getString("preview_digest"), r.getLong("operator_id"), r.getString("state"), r.getObject("expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), r.getString("request_json"), r.getString("preview_json"), r.getString("receipt_json")); }
    private void owner(Row row, Long actor) { if (row == null) throw error(404, "INTAKE_NOT_FOUND", "入库办理记录不存在"); if (!row.owner().equals(actor)) throw error(403, "INTAKE_OWNER_REQUIRED", "仅原操作者可查看和确认本次办理"); }
    private View view(Row row) { Plan p = read(row.planJson(), Plan.class); Receipt receipt = row.receiptJson() == null ? null : read(row.receiptJson(), Receipt.class); return new View(row.id(), row.state(), row.digest(), row.expiresAt().toString(), p.source(), p.lines(), p.books(), p.purchaseProgress(), p.warnings(), receipt); }
    private String write(Object o) { try { return json.writeValueAsString(o); } catch (Exception e) { throw error(500, "INTAKE_SAVE_FAILED", "入库记录处理失败，请查询办理结果"); } }
    private <T> T read(String source, Class<T> type) { try { return json.readValue(source, type); } catch (Exception e) { throw error(500, "INTAKE_SAVE_FAILED", "入库记录读取失败，请查询办理结果"); } }
    private Long insert(String sql, Object... args) { GeneratedKeyHolder key = new GeneratedKeyHolder(); jdbc.update(connection -> { var ps = connection.prepareStatement(sql, new String[]{"id"}); for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]); return ps; }, key); return Objects.requireNonNull(key.getKey()).longValue(); }
}
