package com.library.controller;
import com.library.common.*;
import com.library.dto.LocationContracts.StructureEvent;
import com.library.service.LocationMaintenanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping("/location-structure-events")
@PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
public class LocationStructureEventController {
 private final LocationMaintenanceService locations;
 @GetMapping public Result<PageResult<StructureEvent>> events(@RequestParam String entityType,@RequestParam Long entityId,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return Result.success(locations.events(entityType,entityId,page,size));}
 @GetMapping("/{id}") public Result<StructureEvent> event(@PathVariable Long id){return Result.success(locations.event(id));}
}
