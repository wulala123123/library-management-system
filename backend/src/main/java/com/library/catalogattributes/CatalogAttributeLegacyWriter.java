package com.library.catalogattributes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
/** Required Spring hook; old manually constructed V8/V10 helpers retain their constructor. */
@Component
public class CatalogAttributeLegacyWriter {
    private final JdbcTemplate jdbc;
    public CatalogAttributeLegacyWriter(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void title(long id,String before,String after){CatalogAttributeHistory.recordLegacyTitleChange(jdbc,id,before,after,"LEGACY_BOOK_METADATA");}
}
