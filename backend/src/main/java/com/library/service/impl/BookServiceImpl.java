package com.library.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.library.common.PageResult;
import com.library.dto.BookRequest;
import com.library.entity.Book;
import com.library.entity.BookCategory;
import com.library.entity.BorrowRecord;
import com.library.mapper.BookCategoryMapper;
import com.library.mapper.BookMapper;
import com.library.mapper.BorrowRecordMapper;
import com.library.service.BookService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookServiceImpl implements BookService {

    private final BookMapper bookMapper;
    private final BookCategoryMapper categoryMapper;
    private final BorrowRecordMapper borrowRecordMapper;
    private final com.library.mapper.BookCopyMapper copyMapper;
    private final com.library.service.IntakeCatalog intakeCatalog;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Value("${file.upload-dir:uploads}")
    private String uploadDir;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp");
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp"
    );
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;

    @Override
    public PageResult<Book> getPage(int page, int size, String keyword, Long categoryId, Integer status) {
        Page<Book> pageParam = new Page<>(page, size);
        IPage<Book> result = bookMapper.selectPageWithCategory(pageParam, keyword, categoryId, status);
        if (!result.getRecords().isEmpty()) {
            java.util.Set<Long> categoryIds = result.getRecords().stream()
                    .map(Book::getCategoryId)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            if (!categoryIds.isEmpty()) {
                java.util.Map<Long, String> categoryNameMap = categoryMapper.selectBatchIds(categoryIds).stream()
                        .collect(java.util.stream.Collectors.toMap(BookCategory::getId, BookCategory::getName));
                result.getRecords().forEach(b -> {
                    if (b.getCategoryId() != null) {
                        b.setCategoryName(categoryNameMap.get(b.getCategoryId()));
                    }
                });
            }
        }
        return new PageResult<>(result.getRecords(), result.getTotal(), (long) page, (long) size);
    }

    @Override
    public Book getById(Long id) {
        Book book = bookMapper.lockById(id);
        if (book == null) {
            throw new RuntimeException("图书不存在");
        }
        if (book.getCategoryId() != null) {
            BookCategory cat = categoryMapper.selectById(book.getCategoryId());
            if (cat != null) book.setCategoryName(cat.getName());
        }
        return book;
    }

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Book create(BookRequest request) {
        throw com.library.service.IntakeCatalog.error(409, "INTAKE_PREVIEW_REQUIRED", "新增库存请先预览并确认实体册入库");
    }

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Book update(BookRequest request) {
        if (request.getId() == null) {
            throw new RuntimeException("图书ID不能为空");
        }

        bookMapper.lockCatalog();
        Book book = bookMapper.lockById(request.getId());
        if (book == null) {
            throw new RuntimeException("图书不存在");
        }

        if (!java.util.Objects.equals(request.getStockTotal(), book.getStockTotal()))
            throw com.library.service.IntakeCatalog.error(409, "STOCK_EDIT_REQUIRES_INTAKE", "库存变化请通过实体册入库办理");
        intakeCatalog.updateAll(book.getId(), new com.library.dto.InventoryIntakeContracts.Metadata(request.getIsbn(), request.getTitle(),
                request.getAuthor(), request.getPublisher(), request.getPublishDate(), request.getCategoryId(), request.getPrice(), request.getPages(),
                request.getCoverImage(), request.getDescription(), request.getLocation(), request.getStatus() == null ? book.getStatus() : request.getStatus()));
        return intakeCatalog.view(book.getId()).book();
    }

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void delete(Long id) {
        bookMapper.lockCatalog();
        Book book = bookMapper.lockById(id);
        if (book == null) {
            throw new RuntimeException("图书不存在");
        }
        Long activeBorrows = borrowRecordMapper.selectCount(
                new LambdaQueryWrapper<BorrowRecord>().eq(BorrowRecord::getBookId, id).in(BorrowRecord::getStatus, "BORROWING", "OVERDUE")
        );
        if (activeBorrows > 0) {
            throw new RuntimeException("该图书有未归还的借阅记录，无法删除");
        }
        if (book.getStockTotal() > 0 || book.getStockAvailable() > 0 || copyMapper.selectCount(new LambdaQueryWrapper<com.library.entity.BookCopy>().eq(com.library.entity.BookCopy::getBookId, id)) > 0)
            throw com.library.service.IntakeCatalog.error(409, "BOOK_HAS_INVENTORY", "书目仍有库存或已登记实体册，无法删除");
        bookMapper.deleteById(id);
    }

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void updateStatus(Long id, Integer status) {
        if (status == null || status != 0 && status != 1) com.library.service.IntakeCatalog.invalid();
        bookMapper.lockCatalog();
        Book book = bookMapper.lockById(id);
        if (book == null) {
            throw new RuntimeException("图书不存在");
        }
        jdbc.update("UPDATE book SET status=?,update_time=CURRENT_TIMESTAMP WHERE id=? AND deleted=0", status, id);
    }

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public String uploadBookCover(Long bookId, MultipartFile file) {
        if (file.isEmpty()) {
            throw new RuntimeException("请选择要上传的文件");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new RuntimeException("图片大小不能超过5MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new RuntimeException("仅支持 JPG、PNG、GIF、WEBP 格式的图片");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isEmpty()) {
            throw new RuntimeException("文件名不能为空");
        }
        if (originalName.contains("..") || originalName.contains("/") || originalName.contains("\\")) {
            throw new RuntimeException("文件名包含非法字符");
        }
        int lastDotIndex = originalName.lastIndexOf(".");
        if (lastDotIndex <= 0) {
            throw new RuntimeException("文件扩展名无效");
        }
        String ext = originalName.substring(lastDotIndex).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new RuntimeException("仅支持 JPG、PNG、GIF、WEBP 格式的图片");
        }
        bookMapper.lockCatalog();
        Book book = bookMapper.lockById(bookId);
        if (book == null) {
            throw new RuntimeException("图书不存在");
        }
        try {
            String datePath = LocalDate.now().toString();
            String fileName = UUID.randomUUID() + ext;
            Path baseDir = Paths.get(uploadDir).toAbsolutePath().normalize();
            Path dirPath = baseDir.resolve(datePath);
            Files.createDirectories(dirPath);
            Path filePath = dirPath.resolve(fileName).toAbsolutePath().normalize();
            if (!filePath.startsWith(baseDir)) {
                throw new RuntimeException("非法文件路径");
            }
            file.transferTo(filePath.toFile());
            String relativePath = "/uploads/" + datePath + "/" + fileName;
            jdbc.update("UPDATE book SET cover_image=?,update_time=CURRENT_TIMESTAMP WHERE id=? AND deleted=0", relativePath, bookId);
            return relativePath;
        } catch (IOException e) {
            throw new RuntimeException("图片上传失败，请重试");
        }
    }
}
