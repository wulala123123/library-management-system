package com.library.catalogidentity;

import com.fasterxml.jackson.databind.JsonNode;
import com.library.common.Result;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/catalog-identities")
public class CatalogIdentityController {
    private final CatalogIdentityService service;
    public CatalogIdentityController(CatalogIdentityService service) { this.service = service; }

    @GetMapping("/publishers")
    public Result<JsonNode> publishers(@RequestParam(required=false) String keyword, @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size,Authentication a) {
        return Result.success(service.publishers(keyword,page,size,CatalogIdentityService.actor(a)));
    }

    @GetMapping("/categories")
    public Result<JsonNode> categories(@RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size,Authentication a) {
        return Result.success(service.categories(keyword,page,size,CatalogIdentityService.actor(a)));
    }

    @GetMapping("/books/{bookId}")
    public Result<JsonNode> book(@PathVariable long bookId, Authentication a) {
        return Result.success(service.book(bookId, CatalogIdentityService.actor(a)));
    }

    @PostMapping("/previews")
    public Result<JsonNode> preview(@RequestBody JsonNode input, Authentication a) {
        return Result.success(service.preview(input, CatalogIdentityService.actor(a)));
    }

    @PostMapping("/{requestId}/confirm")
    public Result<JsonNode> confirm(@PathVariable long requestId, @RequestBody JsonNode input, Authentication a) {
        return Result.success(service.confirm(requestId, input, CatalogIdentityService.actor(a)));
    }

    @GetMapping("/{requestId}")
    public Result<JsonNode> request(@PathVariable long requestId, Authentication a) {
        return Result.success(service.getRequest(requestId, CatalogIdentityService.actor(a)));
    }

    @GetMapping("/requests/by-key")
    public Result<JsonNode> byKey(@RequestParam String key, Authentication a) {
        return Result.success(service.getByKey(key, CatalogIdentityService.actor(a)));
    }
}
