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
    private final RestaurantReportService reports;

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
    @PreAuthorize("@access.has('SALES_HISTORY_VIEW') or @access.has('TABLES_MANAGE')")
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
    @PreAuthorize("@access.has('SALES_CREATE') or @access.has('KITCHEN_STATUS_UPDATE')")
    public KitchenOrderService.OrderDto cancel(@PathVariable UUID id, @Valid @RequestBody(required = false) KitchenOrderService.CancelRequest r) {
        return kitchen.cancel(id, r == null ? new KitchenOrderService.CancelRequest(null, false) : r);
    }

    @PostMapping("/orders/{id}/accept")
    @PreAuthorize("@access.has('SALES_CREATE') or @access.has('KITCHEN_STATUS_UPDATE')")
    public KitchenOrderService.OrderDto accept(@PathVariable UUID id) { return kitchen.accept(id); }

    @PostMapping("/orders/{id}/reverse")
    @PreAuthorize("@access.has('SALES_VOID')")
    public KitchenOrderService.OrderDto reverse(@PathVariable UUID id, @Valid @RequestBody KitchenOrderService.ReverseRequest r) { return kitchen.reverse(id, r); }

    @PutMapping("/items/{id}")
    @PreAuthorize("@access.has('SALES_CREATE') or @access.has('KITCHEN_STATUS_UPDATE')")
    public KitchenOrderService.OrderDto updateItem(@PathVariable UUID id, @Valid @RequestBody KitchenOrderService.UpdateItemRequest r) { return kitchen.updateItem(id, r); }

    @PostMapping("/items/{id}/cancel")
    @PreAuthorize("@access.has('SALES_CREATE') or @access.has('KITCHEN_STATUS_UPDATE')")
    public KitchenOrderService.OrderDto cancelItem(@PathVariable UUID id, @Valid @RequestBody(required = false) KitchenOrderService.CancelRequest r) {
        return kitchen.cancelItem(id, r == null ? new KitchenOrderService.CancelRequest(null, false) : r);
    }

    // ----- menu availability -----
    public record AvailabilityRequest(boolean soldOut) {}

    @PostMapping("/products/{productId}/availability")
    @PreAuthorize("@access.has('PRODUCTS_EDIT') or @access.has('KITCHEN_STATUS_UPDATE')")
    public AvailabilityRequest availability(@PathVariable UUID productId, @RequestBody AvailabilityRequest r) { return new AvailabilityRequest(menu.setSoldOut(productId, r.soldOut())); }

    // ----- reports -----
    @GetMapping("/reports")
    @PreAuthorize("@access.has('REPORTS_SALES')")
    public RestaurantReportService.Report report(@RequestParam(defaultValue = "last7") String range,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        return reports.report(range, from, to);
    }

    // ----- kitchen -----
    @GetMapping("/kitchen")
    @PreAuthorize("@access.has('KITCHEN_VIEW')")
    public List<KitchenOrderService.OrderDto> board() { return kitchen.board(); }

    @PostMapping("/items/{id}/status")
    @PreAuthorize("@access.has('KITCHEN_STATUS_UPDATE') or @access.has('KITCHEN_MARK_READY') or @access.has('SALES_CREATE')")
    public KitchenOrderService.OrderDto status(@PathVariable UUID id, @RequestParam String status) { return kitchen.setItemStatus(id, status); }
}
