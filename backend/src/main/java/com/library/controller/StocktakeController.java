package com.library.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.library.common.Result;
import com.library.stocktake.*;
import com.library.stocktake.StocktakeService.Actor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class StocktakeController {
    final StocktakeService stock; final StocktakeResolutionService resolutions;
    Actor actor(Authentication a){if(a==null)throw StocktakeSupport.fail(401,"STOCKTAKE_ROLE_REQUIRED");return new Actor((Long)a.getPrincipal(),a.getAuthorities().stream().anyMatch(x->x.getAuthority().equals("ROLE_ADMIN")),a.getAuthorities().stream().anyMatch(x->x.getAuthority().equals("ROLE_LIBRARIAN")));}
    @PostMapping("/stocktakes") public Result<JsonNode>create(@RequestBody JsonNode n,Authentication a){return Result.success(stock.create(n,actor(a)));}
    @GetMapping("/stocktakes") public Result<JsonNode>list(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)String state,@RequestParam(required=false)String campaignKey,Authentication a){return Result.success(stock.sessions(page,size,state,campaignKey,actor(a)));}
    @GetMapping("/stocktakes/operations/by-key") public Result<JsonNode>operation(@RequestParam String key,Authentication a){return Result.success(stock.byKey(key,actor(a)));}
    @GetMapping("/stocktakes/{id}") public Result<JsonNode>get(@PathVariable long id,Authentication a){return Result.success(stock.get(id,actor(a)));}
    @GetMapping("/stocktakes/{id}/snapshots/{snapshotId}") public Result<JsonNode>snapshot(@PathVariable long id,@PathVariable long snapshotId,Authentication a){return Result.success(stock.snapshot(id,snapshotId,actor(a)));}
    @PostMapping("/stocktakes/{id}/observations") public Result<JsonNode>observe(@PathVariable long id,@RequestBody JsonNode n,Authentication a){return Result.success(stock.observe(id,n,actor(a)));}
    @PostMapping("/stocktakes/{id}/corrections") public Result<JsonNode>correct(@PathVariable long id,@RequestBody JsonNode n,Authentication a){return Result.success(stock.correct(id,n,actor(a)));}
    @PostMapping("/stocktakes/{id}/coverage") public Result<JsonNode>coverage(@PathVariable long id,@RequestBody JsonNode n,Authentication a){return Result.success(stock.coverage(id,n,actor(a)));}
    @PostMapping("/stocktakes/{id}/change-checks") public Result<JsonNode>check(@PathVariable long id,@RequestBody JsonNode n,Authentication a){return Result.success(stock.check(id,n,actor(a),false));}
    @PostMapping("/stocktakes/{id}/prepares") public Result<JsonNode>prepare(@PathVariable long id,@RequestBody JsonNode n,Authentication a){return Result.success(stock.check(id,n,actor(a),true));}
    @PostMapping("/stocktakes/{id}/rechecks") public Result<JsonNode>recheck(@PathVariable long id,@RequestBody JsonNode n,Authentication a){return Result.success(stock.recheck(id,n,actor(a)));}
    @PostMapping("/stocktakes/{id}/confirm") public Result<JsonNode>confirm(@PathVariable long id,@RequestBody JsonNode n,Authentication a){return Result.success(stock.confirm(id,n,actor(a)));}
    @PostMapping("/stocktakes/{id}/cancel") public Result<JsonNode>cancel(@PathVariable long id,@RequestBody JsonNode n,Authentication a){return Result.success(stock.cancel(id,n,actor(a)));}
    @GetMapping("/stocktakes/{id}/observations") public Result<JsonNode>observations(@PathVariable long id,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)Long copyId,@RequestParam(required=false)String kind,Authentication a){return Result.success(stock.observations(id,page,size,copyId,kind,actor(a)));}
    @GetMapping("/stocktakes/{id}/differences") public Result<JsonNode>differences(@PathVariable long id,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)Long reviewSnapshotId,@RequestParam(required=false)Long copyId,@RequestParam(required=false)String type,Authentication a){return Result.success(stock.differences(id,reviewSnapshotId,type,copyId,page,size,actor(a)));}
    @GetMapping("/stocktakes/{id}/changes") public Result<JsonNode>changes(@PathVariable long id,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)Long snapshotId,@RequestParam(required=false)Long copyId,@RequestParam(required=false)String category,Authentication a){return Result.success(stock.changes(id,snapshotId,category,copyId,page,size,actor(a)));}
    @GetMapping("/stocktakes/{id}/resolutions") public Result<JsonNode>resolutions(@PathVariable long id,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,Authentication a){return Result.success(resolutions.page(id,page,size,actor(a)));}
    @GetMapping("/stocktake-resolution-candidates") public Result<JsonNode>candidates(@RequestParam long differenceId,@RequestParam String workflow,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,Authentication a){return Result.success(resolutions.candidates(differenceId,workflow,page,size,actor(a)));}
    @PostMapping("/stocktake-resolutions") public Result<JsonNode>resolve(@RequestBody JsonNode n,Authentication a){return Result.success(resolutions.resolve(n,actor(a)));}
    @GetMapping({"/stocktake-resolutions/{id}","/stocktake-resolutions/{id}/recover"}) public Result<JsonNode>resolution(@PathVariable long id,Authentication a){return Result.success(resolutions.get(id,actor(a)));}
    @PostMapping("/stocktake-resolutions/{id}/link") public Result<JsonNode>link(@PathVariable long id,@RequestBody JsonNode n,Authentication a){return Result.success(resolutions.change(id,n,actor(a),false));}
    @PostMapping("/stocktake-resolutions/{id}/revoke") public Result<JsonNode>revoke(@PathVariable long id,@RequestBody JsonNode n,Authentication a){return Result.success(resolutions.change(id,n,actor(a),true));}
    @GetMapping("/stocktakes/{id}/export") public ResponseEntity<?>export(@PathVariable long id,@RequestParam long reviewSnapshotId,@RequestParam String format,Authentication a){if(!format.equals("json")&&!format.equals("csv"))StocktakeSupport.invalid();JsonNode x=resolutions.export(id,reviewSnapshotId,actor(a));return format.equals("json")?ResponseEntity.ok(Result.success(x)):ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=stocktake-"+id+"-"+reviewSnapshotId+".csv").body(resolutions.csv(x));}
}
