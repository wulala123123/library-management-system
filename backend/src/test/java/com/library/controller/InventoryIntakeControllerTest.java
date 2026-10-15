package com.library.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.library.dto.BorrowRequest;
import com.library.dto.InventoryIntakeContracts.*;
import com.library.entity.Book;
import com.library.mapper.BookMapper;
import com.library.security.JwtTokenProvider;
import com.library.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:intake-acceptance;DB_CLOSE_DELAY=-1;MODE=MYSQL")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InventoryIntakeControllerTest {
    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper json;
    @Autowired protected BookMapper books;
    @Autowired protected JwtTokenProvider tokens;
    @Autowired protected InventoryIntakeService intakes;
    @Autowired protected BorrowRecordService borrows;

    @Test void previewHasNoBusinessWritesAndStableGeneratedCodesAndOwnerRecovery() throws Exception {
        Book b = book(4, 3); ObjectNode input = manual(b, 2);
        var totals = businessCounts(); JsonNode p = preview(input);
        assertEquals(totals, businessCounts()); assertEquals("PREVIEWED", p.path("state").asText()); assertTrue(p.get("receipt").isNull());
        String generated = p.path("lines").get(0).path("copies").get(0).path("barcode").asText();
        assertTrue(generated.matches("LMS-C-[a-f0-9]{12}4[a-f0-9]{3}[89ab][a-f0-9]{15}"));
        assertEquals(p, preview(input));
        assertEquals(p, data(get("/inventory-intakes/by-key").param("key", input.path("idempotencyKey").asText()), "admin", 200));
        assertEquals(p, data(get("/inventory-intakes/" + p.path("intakeId").asLong()), "admin", 200));
        assertReason(get("/inventory-intakes/" + p.path("intakeId").asLong()), null, "librarian", 403, "INTAKE_OWNER_REQUIRED");
        assertReason(get("/inventory-intakes/by-key").param("key", input.path("idempotencyKey").asText()), null, "librarian", 403, "INTAKE_OWNER_REQUIRED");
        ObjectNode changed = input.deepCopy().put("reason", "其他来源");
        assertReason(post("/inventory-intakes/previews"), changed, "admin", 409, "IDEMPOTENCY_KEY_REUSED");
        for (String key : List.of("intakeId", "state", "previewDigest", "expiresAt", "source", "lines", "books", "purchaseProgress", "warnings", "receipt")) assertTrue(p.has(key), key);
    }

    @Test void confirmPreservesHistoricalDifferencesAndLinksEveryCopyFlowAndActorAndReplay() throws Exception {
        Book b = book(7, 5); jdbc.update("INSERT INTO book_copy(book_id,barcode,status,location) VALUES (?,?,'AVAILABLE',NULL)", b.getId(), unique());
        ObjectNode input = manual(b, 3); JsonNode p = preview(input); assertEquals("HISTORICAL_STOCK_DIFFERENCE", p.path("warnings").get(0).asText());
        JsonNode receipt = confirm(input, p); assertEquals(3, receipt.path("copies").size()); assertEquals(1, receipt.path("flows").size());
        Book after = books.selectById(b.getId()); assertEquals(10, after.getStockTotal()); assertEquals(8, after.getStockAvailable());
        assertEquals(6, after.getStockTotal() - count("book_copy", "book_id=?", b.getId()));
        assertEquals(4, after.getStockAvailable() - count("book_copy", "book_id=? AND status='AVAILABLE'", b.getId()));
        long flow = receipt.path("flows").get(0).path("flowId").asLong();
        assertEquals(3, count("inventory_intake_copy", "intake_id=? AND stock_flow_id=?", p.path("intakeId").asLong(), flow));
        assertEquals(actor("admin"), jdbc.queryForObject("SELECT operator_id FROM stock_flow WHERE id=?", Long.class, flow));
        assertNull(jdbc.queryForObject("SELECT related_id FROM stock_flow WHERE id=?", Long.class, flow));
        for (JsonNode c : receipt.path("copies")) { assertEquals("AVAILABLE", c.path("status").asText()); assertTrue(c.get("location").isNull()); assertTrue(c.get("purchaseItemId").isNull()); }
        jdbc.update("UPDATE inventory_intake_request SET expires_at='2000-01-01 00:00:00' WHERE id=?", p.path("intakeId").asLong());
        jdbc.update("UPDATE book SET title='合成后续编辑',stock_total=stock_total+1,stock_available=stock_available+1 WHERE id=?", b.getId());
        assertEquals(receipt, confirm(input, p)); assertEquals(receipt, data(get("/inventory-intakes/" + p.path("intakeId").asLong()), "admin", 200).path("receipt"));
        assertEquals(3, count("inventory_intake_copy", "intake_id=?", p.path("intakeId").asLong()));
    }

    @Test void newCatalogStartsAtZeroAndSameIsbnLinesAggregateWithoutMergingFlows() throws Exception {
        ObjectNode input = manual(null, 1); String isbn = "N" + unique().substring(0, 19);
        ObjectNode metadata = (ObjectNode) input.path("lines").get(0).path("target").path("newBook"); metadata.put("isbn", isbn).put("pages", 0).put("location", "A区1架").putNull("price");
        ObjectNode second = ((ObjectNode) input.path("lines").get(0)).deepCopy().put("lineKey", unique()); input.withArray("lines").add(second);
        JsonNode p = preview(input); assertEquals(0, count("book", "isbn=?", isbn)); assertEquals(1, p.path("books").size()); assertEquals(0, p.path("books").get(0).path("stockTotal").asInt());
        JsonNode r = confirm(input, p); assertEquals(1, r.path("books").size()); assertEquals(2, r.path("flows").size());
        long id = r.path("books").get(0).path("bookId").asLong(); Book b = books.selectById(id); assertEquals(2, b.getStockTotal()); assertEquals(2, b.getStockAvailable()); assertEquals(0, b.getPages()); assertNull(b.getPrice());
        assertEquals("A区1架", b.getLocation()); for (JsonNode c : r.path("copies")) assertTrue(c.get("location").isNull());
        assertReason(post("/inventory-intakes/previews"), input.deepCopy().put("idempotencyKey", UUID.randomUUID().toString()), "admin", 409, "CATALOG_CONFLICT");
        BorrowRequest br = new BorrowRequest(); br.setBookId(id); br.setUserId(newReader());
        var loan = borrows.borrow(br); assertNotNull(loan.getCopyId()); borrows.returnBook(loan.getId()); assertEquals(2, books.selectById(id).getStockAvailable());
    }

    @Test void exactBarcodesRetainZerosCaseSpacesUnicodeAndSqlTextAsBoundValues() throws Exception {
        Book b = book(0, 0); ObjectNode input = manual(b, 0);
        String mark = unique().substring(0, 8);
        List<String> values = List.of("000Aa" + mark, "000aa" + mark, "000Aa" + mark + " ", " 000Aa" + mark, "零000绘本é😀" + mark, "' OR 1=1 --" + mark, "javascript:plain" + mark, "onload=plain" + mark);
        for (String barcode : values) ((ObjectNode) input.path("lines").get(0)).withArray("copies").add(json.createObjectNode().put("input", "SCANNED").put("barcode", barcode));
        JsonNode p = preview(input), r = confirm(input, p);
        for (int n = 0; n < values.size(); n++) {
            assertEquals(values.get(n), r.path("copies").get(n).path("barcode").asText());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM book_copy WHERE barcode_key=? AND book_id=?", Integer.class, values.get(n).getBytes(StandardCharsets.UTF_8), b.getId()));
        }
        assertEquals(values.size(), books.selectById(b.getId()).getStockTotal()); assertEquals(values.size(), count("book_copy", "book_id=?", b.getId()));
        ObjectNode duplicate = manual(b, 0); ((ObjectNode) duplicate.path("lines").get(0)).withArray("copies").add(json.createObjectNode().put("input", "SCANNED").put("barcode", values.get(0)));
        assertReason(post("/inventory-intakes/previews"), duplicate, "admin", 409, "BARCODE_CONFLICT");
        for (String value : values) assertEquals(1, data(get("/book-copies").param("bookId", b.getId().toString()).param("barcode", value), "admin", 200).path("total").asLong(), value);
        data(get("/book-copies").param("barcode", "javascript:plain").param("keyword", "1=1"), "admin", 400);
        data(get("/book-copies").param("barcode", "javascript:plain", "other"), "admin", 400);
    }

    @Test void malformedUnknownAndCoercedInputsFailWithoutTechnicalOrBusinessWrites() throws Exception {
        Book b = book(0, 0); ObjectNode base = manual(b, 1); List<ObjectNode> invalid = new ArrayList<>();
        invalid.add(base.deepCopy().put("stockTotal", 99)); invalid.add(base.deepCopy().put("reason", "\u0000"));
        ObjectNode numericBarcode = base.deepCopy(); ((ObjectNode) numericBarcode.path("lines").get(0).path("copies").get(0)).put("input", "SCANNED").put("barcode", 123); invalid.add(numericBarcode);
        for (String value : List.of("", "   ", "A\nB", "LMS-C-forbidden", "a".repeat(65), "\uD800")) { ObjectNode v = base.deepCopy(); ((ObjectNode) v.path("lines").get(0).path("copies").get(0)).put("input", "SCANNED").put("barcode", value); invalid.add(v); }
        ObjectNode numericBook = base.deepCopy(); ((ObjectNode) numericBook.path("lines").get(0).path("target")).put("bookId", b.getId().toString()); invalid.add(numericBook);
        ObjectNode unknownCopy = base.deepCopy(); ((ObjectNode) unknownCopy.path("lines").get(0).path("copies").get(0)).put("quantity", 3); invalid.add(unknownCopy);
        ObjectNode newBook = manual(null, 1); ((ObjectNode) newBook.path("lines").get(0).path("target").path("newBook")).put("pages", 1.5); invalid.add(newBook);
        ObjectNode tooMany = manual(b, 1001); invalid.add(tooMany);
        long requests = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_intake_request", Long.class); var counts = businessCounts();
        for (ObjectNode v : invalid) assertReason(post("/inventory-intakes/previews"), v, "admin", 400, "INVALID_INPUT");
        assertEquals(requests, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_intake_request", Long.class)); assertEquals(counts, businessCounts());
        assertReason(post("/inventory-intakes/previews"), "{\"source\":", "admin", 400, "INVALID_INPUT");
    }

    @Test void metadataOnlyUsesExplicitWhitelistAndDigestPreservesUnknownAndKnownZero() throws Exception {
        Book b = book(5, 4); jdbc.update("UPDATE book SET pages=0,price=NULL,description=NULL,location=NULL WHERE id=?", b.getId());
        JsonNode before = data(get("/books/" + b.getId() + "/metadata"), "admin", 200);
        var update = json.createObjectNode().put("expectedMetadataDigest", before.path("metadataDigest").asText());
        update.set("changes", json.createObjectNode().put("description", "javascript:literal onload=plain <script>text</script>"));
        JsonNode after = data(put("/books/" + b.getId() + "/metadata").contentType("application/json").content(json.writeValueAsBytes(update)), "admin", 200);
        assertEquals("javascript:literal onload=plain <script>text</script>", after.path("book").path("description").asText());
        assertTrue(after.path("book").get("price").isNull()); assertEquals(0, after.path("book").path("pages").asInt()); assertTrue(after.path("book").get("location").isNull());
        assertEquals(5, after.path("book").path("stockTotal").asInt()); assertEquals(4, after.path("book").path("stockAvailable").asInt());
        assertReason(put("/books/" + b.getId() + "/metadata"), update, "admin", 409, "PREVIEW_STALE");
        for (ObjectNode changes : List.of(json.createObjectNode().put("stockTotal", 9), json.createObjectNode().put("price", "0"), json.createObjectNode().put("coverImage", "javascript:alert(1)"), json.createObjectNode().putNull("title"))) {
            update.put("expectedMetadataDigest", after.path("metadataDigest").asText()).set("changes", changes);
            assertReason(put("/books/" + b.getId() + "/metadata"), update, "admin", 400, "INVALID_INPUT");
        }
        update.set("changes", json.createObjectNode().putNull("description"));
        assertTrue(data(put("/books/" + b.getId() + "/metadata").contentType("application/json").content(json.writeValueAsBytes(update)), "admin", 200).path("book").get("description").isNull());
    }

    @Test void oldCreateAndQuickStockAndStockEditAndInventoryDeleteAreClosedAtomically() throws Exception {
        Book b = book(2, 1); ObjectNode old = json.createObjectNode().put("isbn", b.getIsbn()).put("title", "不应保存").put("categoryId", category()).put("stockTotal", 3).put("id", b.getId());
        var counts = businessCounts(); assertReason(post("/books"), old, "admin", 409, "INTAKE_PREVIEW_REQUIRED");
        assertReason(put("/books"), old, "admin", 409, "STOCK_EDIT_REQUIRES_INTAKE"); assertEquals(b.getTitle(), books.selectById(b.getId()).getTitle());
        assertReason(put("/purchases/999999/instock"), json.createObjectNode(), "admin", 409, "INTAKE_PREVIEW_REQUIRED");
        assertReason(delete("/books/" + b.getId()), null, "admin", 409, "BOOK_HAS_INVENTORY"); assertEquals(counts, businessCounts());
        old.put("stockTotal", 2); assertEquals("不应保存", data(put("/books").contentType("application/json").content(json.writeValueAsBytes(old)), "admin", 200).path("title").asText());
        Book zero = book(0, 0); jdbc.update("INSERT INTO book_copy(book_id,barcode,status) VALUES (?,?,'原待核实')", zero.getId(), unique());
        assertReason(delete("/books/" + zero.getId()), null, "admin", 409, "BOOK_HAS_INVENTORY");
        Book empty = book(0, 0); data(delete("/books/" + empty.getId()), "admin", 200); assertNull(books.selectById(empty.getId()));
    }

    @Test void rolesAndOwnersCannotConfirmOrReadOtherOperatorsRequests() throws Exception {
        Book b = book(0, 0); ObjectNode input = manual(b, 1); JsonNode p = preview(input);
        ObjectNode confirmation = confirmation(input, p);
        for (var route : List.of(get("/inventory-intakes/" + p.path("intakeId").asLong()), get("/books/" + b.getId() + "/metadata"), post("/inventory-intakes/previews").contentType("application/json").content(json.writeValueAsBytes(input)), post("/inventory-intakes/" + p.path("intakeId").asLong() + "/confirm").contentType("application/json").content(json.writeValueAsBytes(confirmation)))) data(route, "reader", 403);
        mvc.perform(get("/inventory-intakes/" + p.path("intakeId").asLong())).andExpect(status().isUnauthorized());
        assertReason(post("/inventory-intakes/" + p.path("intakeId").asLong() + "/confirm"), confirmation, "librarian", 403, "INTAKE_OWNER_REQUIRED");
        assertReason(get("/inventory-intakes/999999999"), null, "admin", 404, "INTAKE_NOT_FOUND");
        assertEquals(0, books.selectById(b.getId()).getStockTotal());
    }

    @Test void expiredAndStalePreviewNeverWriteAndGeneratedConflictDoesNotRegenerate() throws Exception {
        Book b = book(1, 1); ObjectNode input = manual(b, 1); JsonNode p = preview(input);
        jdbc.update("UPDATE inventory_intake_request SET expires_at='2000-01-01 00:00:00' WHERE id=?", p.path("intakeId").asLong());
        assertReason(post(confirmPath(p)), confirmation(input, p), "admin", 409, "PREVIEW_EXPIRED");
        input.put("idempotencyKey", UUID.randomUUID().toString()); p = preview(input); jdbc.update("UPDATE book SET stock_total=stock_total+1,stock_available=stock_available+1 WHERE id=?", b.getId());
        assertReason(post(confirmPath(p)), confirmation(input, p), "admin", 409, "PREVIEW_STALE");
        input.put("idempotencyKey", UUID.randomUUID().toString()); p = preview(input); String code = p.path("lines").get(0).path("copies").get(0).path("barcode").asText();
        Book unrelated = book(0, 0); jdbc.update("INSERT INTO book_copy(book_id,barcode,status) VALUES (?,?,'AVAILABLE')", unrelated.getId(), code);
        assertReason(post(confirmPath(p)), confirmation(input, p), "admin", 409, "BARCODE_CONFLICT");
        assertEquals(code, data(get("/inventory-intakes/" + p.path("intakeId").asLong()), "admin", 200).path("lines").get(0).path("copies").get(0).path("barcode").asText());
        assertEquals(0, count("inventory_intake_copy", "intake_id=?", p.path("intakeId").asLong()));
    }

    @Test void stockIntegerBoundaryReturnsConflictWithPreservedPreviewAndNoBusinessWrites() throws Exception {
        Book boundary = book(Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 1);
        ObjectNode one = manual(boundary, 1); JsonNode accepted = confirm(one, preview(one));
        assertEquals(Integer.MAX_VALUE, accepted.path("books").get(0).path("stockTotalAfter").asInt());
        assertEquals(Integer.MAX_VALUE, accepted.path("books").get(0).path("stockAvailableAfter").asInt());
        // Exercise total-only overflow and simultaneous total/available overflow in a valid stock state.
        for (Book b : List.of(book(Integer.MAX_VALUE, 1), boundary)) {
            ObjectNode input = manual(b, 1); JsonNode p = preview(input);
            var counts = businessCounts(); Book original = books.selectById(b.getId());
            List<String> requestBefore = intakeCells(p.path("intakeId").asLong());
            JsonNode failure = response(post(confirmPath(p)), confirmation(input, p), "admin", 409);
            assertEquals(409, failure.path("code").asInt()); assertEquals("PREVIEW_STALE", failure.path("data").path("reasonCode").asText());
            assertEquals(p.path("intakeId").asLong(), failure.path("data").path("intakeId").asLong());
            assertEquals(counts, businessCounts()); assertEquals(original, books.selectById(b.getId()));
            assertEquals(p, data(get("/inventory-intakes/" + p.path("intakeId").asLong()), "admin", 200));
            assertEquals(p, data(get("/inventory-intakes/by-key").param("key", input.path("idempotencyKey").asText()), "admin", 200));
            assertEquals(requestBefore, intakeCells(p.path("intakeId").asLong()));
        }
    }

    @Test void purchasePartialAndFullReceiptsKeepProgressAndActualOperatorAndImmutableReplay() throws Exception {
        Book b = book(2, 2); long order = order(b, 3), item = item(order); ObjectNode input = purchase(order, item, 1);
        JsonNode p = preview(input); assertEquals(3, p.path("purchaseProgress").get(0).path("pendingQuantity").asInt());
        JsonNode receipt = confirm(input, p); assertEquals(1, receipt.path("purchaseProgress").get(0).path("instockQuantity").asInt()); assertEquals(2, receipt.path("purchaseProgress").get(0).path("pendingQuantity").asInt());
        assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM purchase_order WHERE id=?", String.class, order));
        ObjectNode rest = purchase(order, item, 2); JsonNode full = confirm(rest, preview(rest));
        assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM purchase_order WHERE id=?", String.class, order)); assertEquals(5, books.selectById(b.getId()).getStockTotal());
        assertEquals(receipt, confirm(input, p)); assertEquals(1, count("stock_flow", "id=? AND related_id=? AND operator_id=?", receipt.path("flows").get(0).path("flowId").asLong(), order, actor("admin")));
        assertEquals(item, full.path("copies").get(0).path("purchaseItemId").asLong());
        assertReason(post("/inventory-intakes/previews"), purchase(order, item, 1), "admin", 409, "PURCHASE_NOT_APPROVED");
        long pendingOrder = order(b, 1); assertReason(post("/inventory-intakes/previews"), purchase(pendingOrder, item(pendingOrder), 2), "admin", 409, "QUANTITY_EXCEEDS_PENDING");
        jdbc.update("UPDATE purchase_item SET book_title='合成冲突' WHERE order_id=?", pendingOrder);
        assertReason(post("/inventory-intakes/previews"), purchase(pendingOrder, item(pendingOrder), 1), "admin", 409, "CATALOG_CONFLICT");
    }

    @Test void failedReceiptAuditRollsBackNewBookCopiesStockFlowsAndPurchaseProgress() throws Exception {
        ObjectNode input = manual(null, 2); JsonNode p = preview(input); var counts = businessCounts();
        installFailureTrigger();
        try { JsonNode body = response(post(confirmPath(p)), confirmation(input, p), "admin", 500); assertEquals("INTAKE_SAVE_FAILED", body.path("data").path("reasonCode").asText()); assertFalse(body.toString().contains("private-sql")); }
        finally { jdbc.execute("DROP TRIGGER intake_receipt_fail"); }
        assertEquals(counts, businessCounts()); assertEquals("PREVIEWED", data(get("/inventory-intakes/" + p.path("intakeId").asLong()), "admin", 200).path("state").asText());
        assertEquals(2, confirm(input, p).path("copies").size());
        Book b = book(0, 0); long order = order(b, 2); ObjectNode purchase = purchase(order, item(order), 2); JsonNode pp = preview(purchase);
        installFailureTrigger(); try { response(post(confirmPath(pp)), confirmation(purchase, pp), "admin", 500); } finally { jdbc.execute("DROP TRIGGER intake_receipt_fail"); }
        assertEquals(0, jdbc.queryForObject("SELECT instock_quantity FROM purchase_item WHERE order_id=?", Integer.class, order)); assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM purchase_order WHERE id=?", String.class, order)); assertEquals(0, books.selectById(b.getId()).getStockTotal());
    }

    @Test void purchaseSameIsbnItemsShareOneNewCatalogAndBlankIsbnKeepsItsSourceKey() throws Exception {
        Book descriptor = new Book(); descriptor.setIsbn("P" + unique().substring(0, 19)); descriptor.setTitle("合成多明细新书");
        long order = order(descriptor, 1), first = item(order);
        jdbc.update("INSERT INTO purchase_item(order_id,book_title,isbn,quantity,instock_quantity) VALUES (?,?,?,2,0)", order, descriptor.getTitle(), descriptor.getIsbn());
        long second = jdbc.queryForObject("SELECT MAX(id) FROM purchase_item WHERE order_id=?", Long.class, order);
        ObjectNode input = purchase(order, first, 1), additional = purchase(order, second, 2);
        input.withArray("lines").add(additional.path("lines").get(0)); JsonNode p = preview(input);
        assertEquals(1, p.path("books").size()); assertEquals(0, count("book", "isbn=?", descriptor.getIsbn()));
        JsonNode r = confirm(input, p); assertEquals(1, r.path("books").size()); assertEquals(2, r.path("flows").size()); assertEquals(3, r.path("copies").size());
        assertEquals(2, r.path("purchaseProgress").size()); assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM purchase_order WHERE id=?", String.class, order));
        assertEquals(3, books.selectById(r.path("books").get(0).path("bookId").asLong()).getStockAvailable());
        descriptor.setIsbn(null); long blankOrder = order(descriptor, 1), blankItem = item(blankOrder); ObjectNode blank = purchase(blankOrder, blankItem, 1);
        JsonNode bp = preview(blank); assertEquals("PO-" + blankOrder + "-" + blankItem, bp.path("books").get(0).path("isbn").asText());
        JsonNode br = confirm(blank, bp); assertEquals(blankItem, br.path("copies").get(0).path("purchaseItemId").asLong());
    }

    @Test void simultaneousDifferentKeysWithSameScannedCodeHaveOneWinnerAndKeepOriginalPreview() throws Exception {
        Book a = book(0, 0), b = book(0, 0); String code = "000race-" + unique(); ObjectNode first = manual(a, 1), second = manual(b, 1);
        for (ObjectNode r : List.of(first, second)) ((ObjectNode) r.path("lines").get(0).path("copies").get(0)).put("input", "SCANNED").put("barcode", code);
        JsonNode ap = preview(first), bp = preview(second); List<Object> results = parallel(() -> serviceConfirm(first, ap), () -> serviceConfirm(second, bp));
        assertEquals(1, results.stream().filter(Receipt.class::isInstance).count()); assertEquals(1, results.stream().filter(x -> x instanceof InventoryIntakeException e && e.reasonCode().equals("BARCODE_CONFLICT")).count());
        assertEquals(1, books.selectById(a.getId()).getStockTotal() + books.selectById(b.getId()).getStockTotal());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM book_copy WHERE barcode_key=?", Integer.class, code.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void concurrentConfirmReplayAndNewCatalogCompetitionAndCirculationDoNotDuplicateOrDeadlock() throws Exception {
        Book b = book(0, 0); ObjectNode input = manual(b, 2); JsonNode p = preview(input); PreviewRequest dto = json.treeToValue(input, PreviewRequest.class); ConfirmRequest c = json.treeToValue(confirmation(input, p), ConfirmRequest.class);
        List<Object> same = parallel(() -> intakes.confirm(p.path("intakeId").asLong(), c, actor("admin")), () -> intakes.confirm(p.path("intakeId").asLong(), c, actor("admin")));
        assertEquals(same.get(0), same.get(1)); assertEquals(2, books.selectById(b.getId()).getStockTotal());
        ObjectNode a = manual(null, 1), other = a.deepCopy().put("idempotencyKey", UUID.randomUUID().toString());
        JsonNode ap = preview(a), bp = preview(other);
        List<Object> competed = parallel(() -> serviceConfirm(a, ap), () -> serviceConfirm(other, bp));
        assertEquals(1, competed.stream().filter(Receipt.class::isInstance).count()); assertEquals(1, competed.stream().filter(x -> x instanceof InventoryIntakeException e && e.reasonCode().equals("PREVIEW_STALE")).count());
        Book circulate = book(0, 0); ObjectNode initial = manual(circulate, 1); confirm(initial, preview(initial)); ObjectNode addition = manual(circulate, 1); JsonNode additionPreview = preview(addition);
        long reader = newReader(); BorrowRequest borrow = new BorrowRequest(); borrow.setBookId(circulate.getId()); borrow.setUserId(reader);
        List<Object> race = parallel(() -> serviceConfirm(addition, additionPreview), () -> borrows.borrow(borrow));
        if (race.get(0) instanceof InventoryIntakeException) { addition.put("idempotencyKey", UUID.randomUUID().toString()); confirm(addition, preview(addition)); }
        assertTrue(race.get(1) instanceof com.library.entity.BorrowRecord); assertNotNull(((com.library.entity.BorrowRecord) race.get(1)).getCopyId());
        assertEquals(2, books.selectById(circulate.getId()).getStockTotal()); assertEquals(1, books.selectById(circulate.getId()).getStockAvailable()); assertEquals(2, count("book_copy", "book_id=?", circulate.getId()));
    }

    protected void installFailureTrigger() { jdbc.execute("CREATE TRIGGER intake_receipt_fail BEFORE UPDATE ON inventory_intake_request FOR EACH ROW CALL 'com.library.controller.InventoryIntakeControllerTest$FailReceipt'"); }
    private List<String> intakeCells(long id) {
        return jdbc.queryForObject("SELECT * FROM inventory_intake_request WHERE id=?", (rs, row) -> {
            List<String> cells = new ArrayList<>(); for (int c = 1; c <= rs.getMetaData().getColumnCount(); c++) cells.add(rs.getString(c)); return cells;
        }, id);
    }
    public static class FailReceipt implements org.h2.api.Trigger { @Override public void fire(Connection c, Object[] oldRow, Object[] newRow) throws SQLException { if ("COMMITTED".equals(newRow[9])) throw new SQLException("private-sql-audit-fixture"); } }
    protected Book book(int total, int available) { Book b = new Book(); b.setIsbn("I" + unique().substring(0, 19)); b.setTitle("合成入库书目"); b.setStockTotal(total); b.setStockAvailable(available); b.setStatus(1); books.insert(b); return b; }
    protected long category() { return jdbc.queryForObject("SELECT MIN(id) FROM book_category WHERE deleted=0", Long.class); }
    protected String unique() { return UUID.randomUUID().toString().replace("-", ""); }
    protected ObjectNode manual(Book b, int quantity) {
        ObjectNode input = json.createObjectNode().put("idempotencyKey", UUID.randomUUID().toString()).put("reason", "合成隔离验收入库"); input.set("source", json.createObjectNode().put("type", "MANUAL"));
        ObjectNode target = json.createObjectNode(); if (b != null) target.put("bookId", b.getId()); else target.set("newBook", json.createObjectNode().put("isbn", "I" + unique().substring(0, 19)).put("title", "合成新书").put("categoryId", category()));
        ObjectNode line = json.createObjectNode().put("lineKey", unique()); line.set("target", target); var copies = line.putArray("copies"); for (int i = 0; i < quantity; i++) copies.add(json.createObjectNode().put("input", "GENERATE")); input.putArray("lines").add(line); return input;
    }
    protected ObjectNode purchase(long order, long item, int n) { ObjectNode r = manual(bookReference(), n); r.set("source", json.createObjectNode().put("type", "PURCHASE").put("purchaseOrderId", order)); ((ObjectNode) r.path("lines").get(0)).set("target", json.createObjectNode().put("purchaseItemId", item)); return r; }
    private Book bookReference() { Book b = new Book(); b.setId(1L); return b; }
    protected long order(Book b, int n) { jdbc.update("INSERT INTO purchase_order(order_no,applicant_id,status) VALUES (?,?,'APPROVED')", unique(), actor("librarian")); long id = jdbc.queryForObject("SELECT MAX(id) FROM purchase_order", Long.class); jdbc.update("INSERT INTO purchase_item(order_id,book_title,isbn,author,publisher,category_id,quantity,instock_quantity,price) VALUES (?,?,?,?,?,?,?,0,?)", id, b.getTitle(), b.getIsbn(), b.getAuthor(), b.getPublisher(), b.getCategoryId(), n, b.getPrice()); return id; }
    protected long item(long order) { return jdbc.queryForObject("SELECT MIN(id) FROM purchase_item WHERE order_id=?", Long.class, order); }
    protected long actor(String name) { return jdbc.queryForObject("SELECT id FROM sys_user WHERE username=? AND deleted=0", Long.class, name); }
    protected long newReader() { String name = "intake_" + unique().substring(0, 12); jdbc.update("INSERT INTO sys_user(username,password,real_name,role,status) VALUES (?,'synthetic-only','合成入库读者','READER',1)", name); return actor(name); }
    protected JsonNode preview(ObjectNode body) throws Exception { return response(post("/inventory-intakes/previews"), body, "admin", 200).path("data"); }
    protected ObjectNode confirmation(ObjectNode input, JsonNode p) { return json.createObjectNode().put("idempotencyKey", input.path("idempotencyKey").asText()).put("previewDigest", p.path("previewDigest").asText()); }
    protected String confirmPath(JsonNode p) { return "/inventory-intakes/" + p.path("intakeId").asLong() + "/confirm"; }
    protected JsonNode confirm(ObjectNode body, JsonNode p) throws Exception { return response(post(confirmPath(p)), confirmation(body, p), "admin", 200).path("data"); }
    protected Receipt serviceConfirm(ObjectNode body, JsonNode p) throws Exception { return intakes.confirm(p.path("intakeId").asLong(), json.treeToValue(confirmation(body, p), ConfirmRequest.class), actor("admin")); }
    protected JsonNode data(MockHttpServletRequestBuilder r, String role, int expected) throws Exception { return response(r, null, role, expected).path("data"); }
    protected JsonNode response(MockHttpServletRequestBuilder r, Object body, String role, int expected) throws Exception {
        if (role != null) { long id = actor(role); String actual = jdbc.queryForObject("SELECT role FROM sys_user WHERE id=?", String.class, id); r.header("Authorization", "Bearer " + tokens.generateToken(id, role, actual, 0L)); }
        if (body != null) r.contentType("application/json").content(body instanceof String s ? s.getBytes(StandardCharsets.UTF_8) : json.writeValueAsBytes(body));
        String result = mvc.perform(r).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8); return json.readTree(result);
    }
    protected void assertReason(MockHttpServletRequestBuilder r, Object body, String role, int code, String reason) throws Exception { JsonNode result = response(r, body, role, code); assertEquals(code, result.path("code").asInt()); assertEquals(reason, result.path("data").path("reasonCode").asText()); assertTrue(result.path("data").has("intakeId")); }
    protected long count(String table, String clause, Object... args) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + clause, Long.class, args); }
    protected Map<String, Long> businessCounts() { Map<String, Long> result = new TreeMap<>(); for (String t : List.of("book", "book_copy", "stock_flow", "purchase_order", "purchase_item", "inventory_intake_copy", "borrow_record")) result.put(t, count(t, "1=1")); return result; }
    @SafeVarargs protected final List<Object> parallel(Callable<Object>... actions) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(actions.length); CountDownLatch start = new CountDownLatch(1); List<Future<Object>> futures = new ArrayList<>();
        try { for (Callable<Object> action : actions) futures.add(pool.submit(() -> { start.await(); try { return action.call(); } catch (InventoryIntakeException e) { return e; } })); start.countDown(); List<Object> result = new ArrayList<>(); for (Future<Object> f : futures) result.add(f.get(30, TimeUnit.SECONDS)); return result; }
        finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }
}
