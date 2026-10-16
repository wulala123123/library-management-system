package com.library.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.core.JsonGenerator;
import com.library.entity.Book;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

public final class InventoryIntakeContracts {
    private InventoryIntakeContracts() {}
    public static class StringValue extends StdDeserializer<String> {
        public StringValue() { super(String.class); }
        @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING)
                return (String) context.handleUnexpectedToken(String.class, parser);
            return parser.getText();
        }
    }
    public abstract static class StrictInput {
        @JsonAnySetter public void unknown(String field, Object value) { throw new IllegalArgumentException("不支持的入库字段"); }
    }
    @Data @EqualsAndHashCode(callSuper = false)
    public static class PreviewRequest extends StrictInput {
        @JsonDeserialize(using = StringValue.class) private String idempotencyKey;
        private SourceInput source;
        @JsonDeserialize(using = StringValue.class) private String reason;
        private List<LineInput> lines;
    }
    @Data @EqualsAndHashCode(callSuper = false)
    public static class SourceInput extends StrictInput {
        @JsonDeserialize(using = StringValue.class) private String type;
        @JsonDeserialize(using = MembershipPlanNumberDeserializer.LongValue.class) private Long purchaseOrderId;
    }
    @Data @EqualsAndHashCode(callSuper = false)
    public static class TargetInput extends StrictInput {
        @JsonDeserialize(using = MembershipPlanNumberDeserializer.LongValue.class) private Long bookId;
        private JsonNode newBook;
        @JsonDeserialize(using = MembershipPlanNumberDeserializer.LongValue.class) private Long purchaseItemId;
    }
    @Data @EqualsAndHashCode(callSuper = false)
    public static class LineInput extends StrictInput {
        @JsonDeserialize(using = StringValue.class) private String lineKey;
        private TargetInput target;
        private List<CopyInput> copies;
    }
    @Data @EqualsAndHashCode(callSuper = false)
    public static class CopyInput extends StrictInput {
        @JsonDeserialize(using = StringValue.class) private String input;
        @JsonDeserialize(using = StringValue.class) private String barcode;
        @JsonDeserialize(using = StringValue.class) private String location;
    }
    @Data @EqualsAndHashCode(callSuper = false)
    public static class ConfirmRequest extends StrictInput {
        @JsonDeserialize(using = StringValue.class) private String idempotencyKey;
        @JsonDeserialize(using = StringValue.class) private String previewDigest;
    }
    @Data @EqualsAndHashCode(callSuper = false)
    public static class MetadataUpdate extends StrictInput {
        @JsonDeserialize(using = StringValue.class) private String expectedMetadataDigest;
        private JsonNode changes;
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Metadata(String isbn, String title, String author, String publisher, LocalDate publishDate,
                           Long categoryId, BigDecimal price, Integer pages, String coverImage, String description,
                           String location, Integer status) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Source(String type, Long purchaseOrderId) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PreviewCopy(int ordinal, String input, String barcode, String location) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PreviewLine(String lineKey, Long bookId, Metadata newBook, Long purchaseItemId, List<PreviewCopy> copies) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BookSnapshot(Long bookId, String isbn, String title, String metadataDigest, int stockTotal, int stockAvailable,
                               long registeredCopies, long registeredAvailableCopies, long totalDifference, long availableDifference,
                               boolean newBook) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Progress(Long purchaseItemId, int quantity, int instockQuantity, int pendingQuantity, int receivingQuantity) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(Long intakeId, String state, String previewDigest, String expiresAt, Source source, List<PreviewLine> lines,
                       List<BookSnapshot> books, List<Progress> purchaseProgress, List<String> warnings, Receipt receipt,List<LifecycleBook> reconciliationV2ByBook) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LifecycleBook(Long bookId,String isbn,com.library.service.CopyInventoryProjection.V2 reconciliationV2) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BookReceipt(Long bookId, int stockTotalBefore, int stockAvailableBefore, int stockTotalAfter,
                              int stockAvailableAfter, int addedQuantity) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CopyReceipt(Long copyId, Long bookId, String lineKey, int ordinal, String barcode, String input, String location,
                              String status, Long stockFlowId, Long purchaseItemId) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FlowReceipt(Long flowId, Long bookId, String lineKey, Long purchaseItemId, int quantity) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Receipt(Long intakeId, Source source, Long operatorId, String confirmedAt, List<BookReceipt> books,
                          List<CopyReceipt> copies, List<FlowReceipt> flows, List<Progress> purchaseProgress) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MetadataView(@JsonSerialize(using = MetadataBookSerializer.class) Book book, String metadataDigest) {}
    /** Explicit nulls for this endpoint without changing the legacy Book response serialization. */
    public static class MetadataBookSerializer extends JsonSerializer<Book> {
        @Override public void serialize(Book b, JsonGenerator g, SerializerProvider provider) throws IOException {
            g.writeStartObject();
            g.writeObjectField("id", b.getId()); g.writeObjectField("isbn", b.getIsbn()); g.writeObjectField("title", b.getTitle());
            g.writeObjectField("author", b.getAuthor()); g.writeObjectField("publisher", b.getPublisher()); g.writeObjectField("publishDate", b.getPublishDate());
            g.writeObjectField("categoryId", b.getCategoryId()); g.writeObjectField("price", b.getPrice()); g.writeObjectField("pages", b.getPages());
            g.writeObjectField("coverImage", b.getCoverImage()); g.writeObjectField("description", b.getDescription()); g.writeObjectField("location", b.getLocation());
            g.writeObjectField("stockTotal", b.getStockTotal()); g.writeObjectField("stockAvailable", b.getStockAvailable()); g.writeObjectField("status", b.getStatus());
            g.writeObjectField("createTime", b.getCreateTime()); g.writeObjectField("updateTime", b.getUpdateTime()); g.writeObjectField("categoryName", b.getCategoryName());
            g.writeEndObject();
        }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ErrorData(String reasonCode, Long intakeId) {}
}
