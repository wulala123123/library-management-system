package com.library.controller;

import com.library.common.Result;
import com.library.dto.BookCopyActionContracts.*;
import com.library.service.BookCopyActionService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
public class BookCopyActionController {
    private final BookCopyActionService actions;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    @PostMapping("/book-copy-actions/previews") public Result<View> preview(@RequestBody PreviewRequest request,Authentication auth){return Result.success(actions.preview(request,(Long)auth.getPrincipal(),admin(auth)));}
    @PostMapping("/book-copy-actions/v2/previews") public Result<?> previewV2(@RequestBody JsonNode request,Authentication auth){return Result.success(actions.previewV2(request,(Long)auth.getPrincipal(),admin(auth)));}
    @PostMapping("/book-copy-actions/{id}/confirm") public Result<JsonNode> confirm(@PathVariable Long id,@RequestBody ConfirmRequest request,Authentication auth){return Result.success(json.valueToTree(actions.confirmAny(id,request,(Long)auth.getPrincipal(),admin(auth))));}
    @GetMapping("/book-copy-actions/by-key") public Result<JsonNode> byKey(@RequestParam String key,Authentication auth){return Result.success(json.valueToTree(actions.byKeyAny(key,(Long)auth.getPrincipal())));}
    @GetMapping("/book-copy-actions/{id}") public Result<JsonNode> get(@PathVariable Long id,Authentication auth){return Result.success(json.valueToTree(actions.getAny(id,(Long)auth.getPrincipal())));}
    @GetMapping("/book-copies/{id}/action-context") public Result<Context> context(@PathVariable Long id,Authentication auth){return Result.success(actions.context(id,admin(auth)));}
    @GetMapping("/book-copies/{id}/actions") public Result<?> history(@PathVariable Long id,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return Result.success(actions.historyAny(id,page,size));}
    private boolean admin(Authentication auth){return auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN"));}
}
