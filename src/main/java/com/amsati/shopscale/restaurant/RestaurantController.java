package com.amsati.shopscale.restaurant;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/restaurant")
@RequiredArgsConstructor
public class RestaurantController {
    private final MenuService menu;
    private final KitchenOrderService kitchen;

    // ----- modifier groups -----
    @GetMapping("/modifier-groups")
    @PreAuthorize("@access.has('PRODUCTS_VIEW') or @access.has('SALES_CREATE')")
    public List<MenuService.GroupDto> groups() { return menu.listGroups(); }

    @PostMapping("/modifier-groups")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('PRODUCTS_EDIT')")
    public MenuService.GroupDto createGroup(@Valid @RequestBody MenuService.GroupRequest r) { return menu.createGroup(r); }

    @PutMapping("/modifier-groups/{id}")
    @PreAuthorize("@access.has('PRODUCTS_EDIT')")
    public MenuService.GroupDto updateGroup(@PathVariable UUID id, @Valid @RequestBody MenuService.GroupRequest r, @RequestParam(required = false) Boolean active) { return menu.updateGroup(id, r, active); }

    @PostMapping("/modifier-groups/{id}/modifiers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('PRODUCTS_EDIT')")
    public MenuService.GroupDto addModifier(@PathVariable UUID id, @Valid @RequestBody MenuService.ModifierRequest r) { return menu.addModifier(id, r); }

    @PutMapping("/modifiers/{id}")
    @PreAuthorize("@access.has('PRODUCTS_EDIT')")
    public MenuService.GroupDto updateModifier(@PathVariable UUID id, @Valid @RequestBody MenuService.ModifierRequest r, @RequestParam(required = false) Boolean active) { return menu.updateModifier(id, r, active); }

    @GetMapping("/products/{productId}/modifier-groups")
    @PreAuthorize("@access.has('PRODUCTS_VIEW') or @access.has('SALES_CREATE')")
    public List<UUID> productGroups(@PathVariable UUID productId) { return menu.groupsOfProduct(productId); }

    @PutMapping("/products/{productId}/modifier-groups")
    @PreAuthorize("@access.has('PRODUCTS_EDIT')")
    public List<UUID> setProductGroups(@PathVariable UUID productId, @RequestBody List<UUID> groupIds) { return menu.setGroupsOfProduct(productId, groupIds); }

    // ----- tables -----
    @GetMapping("/tables")
    @PreAuthorize("@access.has('TABLES_MANAGE') or @access.has('SALES_CREATE') or @access.has('KITCHEN_VIEW')")
    public List<MenuService.TableDto> tables() { return menu.listTables(); }

    @PostMapping("/tables")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('TABLES_MANAGE')")
    public MenuService.TableDto createTable(@Valid @RequestBody MenuService.TableRequest r) { return menu.createTable(r); }

    @PutMapping("/tables/{id}")
    @PreAuthorize("@access.has('TABLES_MANAGE')")
    public MenuService.TableDto updateTable(@PathVariable UUID id, @Valid @RequestBody MenuService.TableRequest r, @RequestParam(required = false) Boolean active) { return menu.updateTable(id, r, active); }

    // ----- orders -----
    @GetMapping("/orders")
    @PreAuthorize("@access.has('SALES_CREATE') or @access.has('TABLES_MANAGE')")
    public List<KitchenOrderService.OrderDto> open() { return kitchen.open(); }

    @GetMapping("/orders/recent")
    @PreAuthorize("@access.has('SALES_VIEW') or @access.has('TABLES_MANAGE')")
    public List<KitchenOrderService.OrderDto> recent() { return kitchen.recent(); }

    @GetMapping("/orders/{id}")
    @PreAuthorize("@access.has('SALES_CREATE') or @access.has('KITCHEN_VIEW')")
    public KitchenOrderService.OrderDto get(@PathVariable UUID id) { return kitchen.get(id); }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('SALES_CREATE')")
    public KitchenOrderService.OrderDto create(@Valid @RequestBody KitchenOrderService.CreateRequest r) { return kitchen.create(r); }

    @PostMapping("/orders/{id}/items")
    @PreAuthorize("@access.has('SALES_CREATE')")
    public KitchenOrderService.OrderDto addItems(@PathVariable UUID id, @Valid @RequestBody KitchenOrderService.AddItemsRequest r) { return kitchen.addItems(id, r); }

    @PostMapping("/orders/{id}/pay")
    @PreAuthorize("@access.has('SALES_CREATE')")
    public KitchenOrderService.OrderDto pay(@PathVariable UUID id, @Valid @RequestBody KitchenOrderService.PayRequest r) { return kitchen.pay(id, r); }

    @PostMapping("/orders/{id}/cancel")
    @PreAuthorize("@access.has('SALES_CREATE')")
    public KitchenOrderService.OrderDto cancel(@PathVariable UUID id) { return kitchen.cancel(id); }

    // ----- kitchen -----
    @GetMapping("/kitchen")
    @PreAuthorize("@access.has('KITCHEN_VIEW')")
    public List<KitchenOrderService.OrderDto> board() { return kitchen.board(); }

    @PostMapping("/items/{id}/status")
    @PreAuthorize("@access.has('KITCHEN_STATUS_UPDATE') or @access.has('KITCHEN_MARK_READY') or @access.has('SALES_CREATE')")
    public KitchenOrderService.OrderDto status(@PathVariable UUID id, @RequestParam String status) { return kitchen.setItemStatus(id, status); }
}
