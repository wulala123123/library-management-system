package com.library.controller;
import com.library.common.*;
import com.library.dto.LocationContracts.*;
import com.library.service.LocationAddressService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping("/location-addresses")
@PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
public class LocationAddressController {
    private final LocationAddressService addresses;
    @GetMapping public Result<PageResult<AddressRow>> page(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)String keyword,@RequestParam(required=false)Long bookshelfId,@RequestParam(required=false)Long storeyId,@RequestParam(defaultValue="false")boolean selectableOnly){return Result.success(addresses.page(page,size,keyword,bookshelfId,storeyId,selectableOnly));}
    @GetMapping("/{id}") public Result<AddressRow> get(@PathVariable Long id){return Result.success(addresses.get(id));}
}
