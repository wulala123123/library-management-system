package com.library.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.library.dto.InventoryIntakeContracts.*;
import com.library.entity.Book;
import com.library.mapper.BookMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** The only metadata writer used by intake and the explicit metadata endpoint. */
@Service
@RequiredArgsConstructor
public class IntakeCatalog {
    private final JdbcTemplate jdbc;
    private final BookMapper books;
    private final ObjectMapper json;
    private com.library.catalogattributes.CatalogAttributeLegacyWriter attributeHistory;
    @org.springframework.beans.factory.annotation.Autowired
    public void setAttributeHistory(com.library.catalogattributes.CatalogAttributeLegacyWriter history) { this.attributeHistory = history; }
    private static final LinkedHashMap<String, String> COLUMNS = new LinkedHashMap<>();
    static {
        COLUMNS.put("isbn", "isbn"); COLUMNS.put("title", "title"); COLUMNS.put("author", "author");
        COLUMNS.put("publisher", "publisher"); COLUMNS.put("publishDate", "publish_date");
        COLUMNS.put("categoryId", "category_id"); COLUMNS.put("price", "price"); COLUMNS.put("pages", "pages");
        COLUMNS.put("coverImage", "cover_image"); COLUMNS.put("description", "description");
        COLUMNS.put("location", "location"); COLUMNS.put("status", "status");
    }
    public Book read(Long id) {
        List<Book> rows = jdbc.query("SELECT * FROM book WHERE id=? AND deleted=0", BeanPropertyRowMapper.newInstance(Book.class), id);
        if (rows.isEmpty()) throw error(404, "BOOK_NOT_FOUND", "图书不存在");
        return rows.get(0);
    }
    public Book byIsbn(String isbn) {
        List<Book> rows = jdbc.query("SELECT * FROM book WHERE isbn=?", BeanPropertyRowMapper.newInstance(Book.class), isbn);
        return rows.isEmpty() ? null : rows.get(0);
    }
    // UNION inherits book.isbn's database collation, including its PAD SPACE behavior.
    public boolean sameIsbn(String left, String right) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT isbn FROM book WHERE 1=0 UNION ALL SELECT ?) candidate WHERE isbn=?", Integer.class, left, right) > 0;
    }
    public Metadata metadata(Book b) {
        return new Metadata(b.getIsbn(), b.getTitle(), b.getAuthor(), b.getPublisher(), b.getPublishDate(), b.getCategoryId(),
                b.getPrice(), b.getPages(), b.getCoverImage(), b.getDescription(), b.getLocation(), b.getStatus());
    }
    public MetadataView view(Long id) {
        Book b = read(id);
        if (b.getCategoryId() != null) {
            List<String> names = jdbc.queryForList("SELECT name FROM book_category WHERE id=? AND deleted=0", String.class, b.getCategoryId());
            if (!names.isEmpty()) b.setCategoryName(names.get(0));
        }
        return new MetadataView(b, digest(metadata(b)));
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MetadataView update(Long id, MetadataUpdate request) {
        if (request == null || request.getExpectedMetadataDigest() == null || !request.getExpectedMetadataDigest().matches("[a-f0-9]{64}")) invalid();
        books.lockCatalog();
        Book b = books.lockById(id);
        if (b == null) throw error(404, "BOOK_NOT_FOUND", "图书不存在");
        if (!digest(metadata(b)).equals(request.getExpectedMetadataDigest())) throw error(409, "PREVIEW_STALE", "书目资料已变化，请重新读取");
        LinkedHashMap<String, Object> changes = parse(request.getChanges(), false);
        if (changes.isEmpty()) invalid();
        updateColumns(id, changes);
        return view(id);
    }
    /** Legacy full metadata forms are supported only with unchanged stock. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void updateAll(Long id, Metadata data) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        JsonNode node = json.valueToTree(data);
        for (String key : COLUMNS.keySet()) values.put(key, value(key, node.get(key), false));
        updateColumns(id, values);
    }
    private void updateColumns(Long id, LinkedHashMap<String, Object> values) {
        var identityBefore = com.library.catalogidentity.CatalogIdentityHistory.book(jdbc,id);
        String oldTitle = values.containsKey("title") ? jdbc.queryForObject("SELECT title FROM book WHERE id=? AND deleted=0", String.class, id) : null;
        if (values.containsKey("isbn")) {
            Book existing = byIsbn((String) values.get("isbn"));
            if (existing != null && !existing.getId().equals(id)) throw error(409, "CATALOG_CONFLICT", "ISBN已被书目占用，请核对");
        }
        List<Object> args = new ArrayList<>(); List<String> assignments = new ArrayList<>();
        values.forEach((key, value) -> { assignments.add(COLUMNS.get(key) + "=?"); args.add(value); });
        args.add(id);
        // Column identifiers come exclusively from the static whitelist; no stock columns are writable.
        if (jdbc.update("UPDATE book SET " + String.join(",", assignments) + ",update_time=CURRENT_TIMESTAMP WHERE id=? AND deleted=0", args.toArray()) != 1)
            throw error(409, "PREVIEW_STALE", "书目已变化，请重新读取");
        if (values.containsKey("title") && attributeHistory != null) attributeHistory.title(id, oldTitle, (String) values.get("title"));
        com.library.catalogidentity.CatalogIdentityHistory.recordBook(jdbc,id,identityBefore,"LEGACY_BOOK_METADATA");
    }
    public Metadata newMetadata(JsonNode input) {
        LinkedHashMap<String, Object> values = parse(input, true);
        return new Metadata((String) values.get("isbn"), (String) values.get("title"), (String) values.get("author"),
                (String) values.get("publisher"), (LocalDate) values.get("publishDate"), (Long) values.get("categoryId"),
                (BigDecimal) values.get("price"), (Integer) values.get("pages"), (String) values.get("coverImage"),
                (String) values.get("description"), (String) values.get("location"), (Integer) values.getOrDefault("status", 1));
    }
    private LinkedHashMap<String, Object> parse(JsonNode node, boolean create) {
        if (node == null || !node.isObject()) invalid();
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        node.fieldNames().forEachRemaining(key -> { if (!COLUMNS.containsKey(key)) invalid(); });
        for (String key : COLUMNS.keySet()) if (node.has(key)) values.put(key, value(key, node.get(key), create));
        if (create) {
            for (String required : List.of("isbn", "title", "categoryId")) if (!values.containsKey(required)) invalid();
            if (values.get("status") == null) values.put("status", 1);
        }
        return values;
    }
    private Object value(String key, JsonNode node, boolean create) {
        boolean nullable = !Set.of("isbn", "title", "categoryId", "status").contains(key);
        if (node == null || node.isNull()) {
            if (!nullable && !(create && key.equals("status"))) invalid();
            return null;
        }
        switch (key) {
            case "categoryId":
                if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() <= 0) invalid();
                long category = node.longValue();
                if (jdbc.queryForObject("SELECT COUNT(*) FROM book_category WHERE id=? AND deleted=0", Long.class, category) != 1) invalid();
                return category;
            case "pages": case "status":
                if (!node.isIntegralNumber() || !node.canConvertToInt()) invalid();
                int n = node.intValue();
                if (key.equals("pages") ? n < 0 || n > 99999 : n != 0 && n != 1) invalid();
                return n;
            case "price":
                if (!node.isNumber()) invalid();
                BigDecimal amount = node.decimalValue();
                if (amount.signum() < 0 || amount.compareTo(new BigDecimal("99999.99")) > 0) invalid();
                try { return amount.setScale(2, RoundingMode.UNNECESSARY); } catch (ArithmeticException e) { invalid(); }
            default:
                if (!node.isTextual()) invalid();
                String s = node.textValue();
                int max = switch (key) { case "isbn" -> 20; case "coverImage" -> 500; case "description" -> 2000; case "location" -> 100; default -> 200; };
                text(s, max, key.equals("isbn") || key.equals("title"));
                if (key.equals("isbn") && !s.matches("[a-zA-Z0-9-]+")) invalid();
                if (key.equals("publishDate")) {
                    if (!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) invalid();
                    try { return LocalDate.parse(s); } catch (RuntimeException e) { invalid(); }
                }
                if (key.equals("location") && !s.matches("[\\p{L}\\d\\-A-Z区层架]*")) invalid();
                if (key.equals("coverImage") && !s.matches("^(https?://[\\w\\-.]+(:\\d+)?(/[^\\s]*)?|/uploads/[^\\s]+)?$")) invalid();
                return s;
        }
    }
    public Long insert(Metadata m) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO book(isbn,title,author,publisher,publish_date,category_id,price,pages,cover_image,description,location,status,stock_total,stock_available) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,0)", new String[]{"id"});
            Object[] args = {m.isbn(), m.title(), m.author(), m.publisher(), m.publishDate(), m.categoryId(), m.price(), m.pages(), m.coverImage(), m.description(), m.location(), m.status()};
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    public String digest(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(sorted(json.valueToTree(value))))); }
        catch (Exception e) { throw error(500, "INTAKE_SAVE_FAILED", "入库记录处理失败，请查询办理结果后重试"); }
    }
    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = json.createObjectNode(); TreeSet<String> keys = new TreeSet<>(); node.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) result.set(key, sorted(node.get(key))); return result;
        }
        if (node.isArray()) { ArrayNode result = json.createArrayNode(); node.forEach(n -> result.add(sorted(n))); return result; }
        return node;
    }
    public static void text(String s, int max, boolean required) {
        if (s == null) { if (required) invalid(); return; }
        if (s.codePointCount(0, s.length()) > max || (required && (s.isBlank() || s.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp))))) invalid();
        for (int i = 0; i < s.length();) {
            char c = s.charAt(i);
            if (Character.isSurrogate(c) && (!Character.isHighSurrogate(c) || i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1)))) invalid();
            int cp = s.codePointAt(i); if (Character.isISOControl(cp)) invalid(); i += Character.charCount(cp);
        }
    }
    public static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    public static void invalid() { throw error(400, "INVALID_INPUT", "入库参数不符合要求，请检查输入"); }
    public static InventoryIntakeException error(int status, String reason, String message) { return new InventoryIntakeException(status, reason, message); }
}
