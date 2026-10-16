package com.library.catalogattributes;
import com.fasterxml.jackson.databind.JsonNode;
import com.library.common.Result;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import static com.library.catalogattributes.CatalogAttributeSupport.*;

@RestController
public class CatalogAttributeController {
    final CatalogAttributeService attributes;final CatalogBookQuery books;
    public CatalogAttributeController(CatalogAttributeService attributes,CatalogBookQuery books){this.attributes=attributes;this.books=books;}
    @GetMapping("/catalog-books")public Result<JsonNode> page(@RequestParam MultiValueMap<String,String> q){return Result.success(books.page(q));}
    @GetMapping("/catalog-books/{bookId}")public Result<JsonNode> book(@PathVariable long bookId){return Result.success(books.get(bookId));}
    @GetMapping("/catalog-books/{bookId}/attributes")public Result<JsonNode> attributes(@PathVariable long bookId,Authentication a){return Result.success(attributes.attributes(bookId,actor(a)));}
    @GetMapping("/catalog-books/{bookId}/source")public Result<JsonNode> source(@PathVariable long bookId,Authentication a){return Result.success(attributes.source(bookId,actor(a)));}
    @GetMapping("/catalog-books/{bookId}/attribute-events")public Result<JsonNode> events(@PathVariable long bookId,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="10")int size,Authentication a){return Result.success(attributes.events(bookId,page,size,actor(a)));}
    @PostMapping("/catalog-attributes/previews")public Result<JsonNode> preview(@RequestBody JsonNode input,Authentication a){return Result.success(attributes.preview(input,actor(a)));}
    @PostMapping("/catalog-attributes/{requestId}/confirm")public Result<JsonNode> confirm(@PathVariable long requestId,@RequestBody JsonNode input,Authentication a){return Result.success(attributes.confirm(requestId,input,actor(a)));}
    @GetMapping("/catalog-attributes/by-key")public Result<JsonNode> byKey(@RequestParam String key,Authentication a){return Result.success(attributes.byKey(key,actor(a)));}
    @GetMapping("/catalog-attributes/{requestId}")public Result<JsonNode> get(@PathVariable long requestId,Authentication a){return Result.success(attributes.get(requestId,actor(a)));}
}
