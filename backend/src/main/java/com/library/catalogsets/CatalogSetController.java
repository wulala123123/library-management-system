package com.library.catalogsets;
import com.fasterxml.jackson.databind.JsonNode;
import com.library.common.Result;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import static com.library.catalogsets.CatalogSetSupport.*;

@RestController
public class CatalogSetController {
    final CatalogSetService service;
    public CatalogSetController(CatalogSetService s){service=s;}
    @GetMapping("/catalog-sets") public Result<JsonNode> list(@RequestParam MultiValueMap<String,String> q,Authentication a){return Result.success(service.list(q,actor(a)));}
    @GetMapping("/catalog-sets/{setId}") public Result<JsonNode> get(@PathVariable long setId,@RequestParam MultiValueMap<String,String> q,Authentication a){return Result.success(service.getSet(setId,q,actor(a)));}
    @GetMapping("/catalog-sets/{setId}/members") public Result<JsonNode> members(@PathVariable long setId,@RequestParam MultiValueMap<String,String> q,Authentication a){return Result.success(service.members(setId,q,actor(a),false,false));}
    @GetMapping("/catalog-sets/{setId}/maintenance-members") public Result<JsonNode> maintenance(@PathVariable long setId,@RequestParam MultiValueMap<String,String> q,Authentication a){return Result.success(service.members(setId,q,actor(a),true,false));}
    @GetMapping("/catalog-sets/books/{bookId}/memberships") public Result<JsonNode> memberships(@PathVariable long bookId,@RequestParam MultiValueMap<String,String> q,Authentication a){return Result.success(service.members(bookId,q,actor(a),false,true));}
    @GetMapping("/catalog-sets/books/contexts") public Result<JsonNode> contexts(@RequestParam MultiValueMap<String,String> q,Authentication a){return Result.success(service.bookContexts(q,actor(a)));}
    @PostMapping("/catalog-sets/previews") public Result<JsonNode> preview(@RequestBody JsonNode input,@RequestParam MultiValueMap<String,String> q,Authentication a){return Result.success(service.preview(input,q,actor(a)));}
    @PostMapping("/catalog-sets/requests/{requestId}/confirm") public Result<JsonNode> confirm(@PathVariable long requestId,@RequestBody JsonNode input,@RequestParam MultiValueMap<String,String> q,Authentication a){return Result.success(service.confirm(requestId,input,q,actor(a)));}
    @PostMapping("/catalog-sets/requests/{requestId}/cancel") public Result<JsonNode> cancel(@PathVariable long requestId,@RequestBody JsonNode input,@RequestParam MultiValueMap<String,String> q,Authentication a){return Result.success(service.cancel(requestId,input,q,actor(a)));}
    @GetMapping("/catalog-sets/requests/by-key") public Result<JsonNode> byKey(@RequestParam MultiValueMap<String,String> q,Authentication a){return Result.success(service.byKey(q,actor(a)));}
    @GetMapping("/catalog-sets/requests/{requestId}") public Result<JsonNode> request(@PathVariable long requestId,@RequestParam MultiValueMap<String,String> q,Authentication a){return Result.success(service.getRequest(requestId,q,actor(a)));}
    @GetMapping("/catalog-sets/{setId}/events") public Result<JsonNode> events(@PathVariable long setId,@RequestParam MultiValueMap<String,String> q,Authentication a){return Result.success(service.events(setId,q,actor(a)));}
    @GetMapping("/catalog-set-reviews") public Result<JsonNode> reviews(@RequestParam MultiValueMap<String,String> q,Authentication a){return Result.success(service.reviews(q,actor(a)));}
    @GetMapping("/catalog-set-reviews/sources") public Result<JsonNode> sources(@RequestParam MultiValueMap<String,String> q,Authentication a){return Result.success(service.sourceDetails(q,actor(a)));}
}
