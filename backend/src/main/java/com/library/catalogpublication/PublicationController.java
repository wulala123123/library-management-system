package com.library.catalogpublication;
import com.fasterxml.jackson.databind.JsonNode;
import com.library.common.Result;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/catalog-publications")
public class PublicationController {
    private final PublicationService service;
    public PublicationController(PublicationService service){this.service=service;}
    @GetMapping("/books/{bookId}") public Result<JsonNode> book(@PathVariable long bookId,Authentication a){return Result.success(service.book(bookId,PublicationSupport.actor(a)));}
    @GetMapping("/books/{bookId}/events") public Result<JsonNode> events(@PathVariable long bookId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,Authentication a){return Result.success(service.events(bookId,page,size,PublicationSupport.actor(a)));}
    @PostMapping("/previews") public Result<JsonNode> preview(@RequestBody JsonNode input,Authentication a){return Result.success(service.preview(input,PublicationSupport.actor(a)));}
    @PostMapping("/{requestId}/confirm") public Result<JsonNode> confirm(@PathVariable long requestId,@RequestBody JsonNode input,Authentication a){return Result.success(service.confirm(requestId,input,PublicationSupport.actor(a)));}
    @GetMapping("/{requestId}") public Result<JsonNode> request(@PathVariable long requestId,Authentication a){return Result.success(service.getRequest(requestId,PublicationSupport.actor(a)));}
    @GetMapping("/requests/by-key") public Result<JsonNode> byKey(@RequestParam String key,Authentication a){return Result.success(service.getByKey(key,PublicationSupport.actor(a)));}
}
