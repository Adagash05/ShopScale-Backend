package com.amsati.shopscale.catering;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Catering API. Viewing needs CATERING_VIEW (money is hidden from view-only staff); everything that changes data needs CATERING_MANAGE, except the preparation checklist. */
@RestController
@RequestMapping("/api/catering")
@RequiredArgsConstructor
public class CateringController {
    private final CateringCatalogService catalog;
    private final CateringEventService events;

    // ----- packages and services -----
    @GetMapping("/packages")
    @PreAuthorize("@access.has('CATERING_VIEW')")
    public List<CateringCatalogService.PackageDto> packages(@RequestParam(defaultValue = "false") boolean all) { return catalog.packages(all); }

    @PostMapping("/packages")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('CATERING_MANAGE')")
    public CateringCatalogService.PackageDto createPackage(@Valid @RequestBody CateringCatalogService.PackageRequest r) { return catalog.savePackage(null, r); }

    @PutMapping("/packages/{id}")
    @PreAuthorize("@access.has('CATERING_MANAGE')")
    public CateringCatalogService.PackageDto updatePackage(@PathVariable UUID id, @Valid @RequestBody CateringCatalogService.PackageRequest r) { return catalog.savePackage(id, r); }

    @GetMapping("/services")
    @PreAuthorize("@access.has('CATERING_VIEW')")
    public List<CateringCatalogService.ServiceDto> services(@RequestParam(defaultValue = "false") boolean all) { return catalog.services(all); }

    @PostMapping("/services")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('CATERING_MANAGE')")
    public CateringCatalogService.ServiceDto createService(@Valid @RequestBody CateringCatalogService.ServiceRequest r) { return catalog.saveService(null, r); }

    @PutMapping("/services/{id}")
    @PreAuthorize("@access.has('CATERING_MANAGE')")
    public CateringCatalogService.ServiceDto updateService(@PathVariable UUID id, @Valid @RequestBody CateringCatalogService.ServiceRequest r) { return catalog.saveService(id, r); }

    // ----- events -----
    @GetMapping("/dashboard")
    @PreAuthorize("@access.has('CATERING_VIEW')")
    public CateringEventService.Dashboard dashboard() { return events.dashboard(); }

    @PostMapping("/quote-preview")
    @PreAuthorize("@access.has('CATERING_MANAGE')")
    public CateringEventService.Quote preview(@Valid @RequestBody CateringEventService.PreviewRequest r) { return events.preview(r); }

    @GetMapping("/events")
    @PreAuthorize("@access.has('CATERING_VIEW')")
    public List<CateringEventService.Row> list(@RequestParam(required = false) String status, @RequestParam(required = false) String q) { return events.list(status, q); }

    @GetMapping("/events/{id}")
    @PreAuthorize("@access.has('CATERING_VIEW')")
    public CateringEventService.EventDto get(@PathVariable UUID id) { return events.get(id); }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('CATERING_MANAGE')")
    public CateringEventService.EventDto create(@Valid @RequestBody CateringEventService.EventRequest r) { return events.create(r); }

    @PutMapping("/events/{id}")
    @PreAuthorize("@access.has('CATERING_MANAGE')")
    public CateringEventService.EventDto update(@PathVariable UUID id, @Valid @RequestBody CateringEventService.EventRequest r) { return events.update(id, r); }

    @PostMapping("/events/{id}/quote")
    @PreAuthorize("@access.has('CATERING_MANAGE')")
    public CateringEventService.EventDto quote(@PathVariable UUID id) { return events.quote(id); }

    @PostMapping("/events/{id}/confirm")
    @PreAuthorize("@access.has('CATERING_MANAGE')")
    public CateringEventService.EventDto confirm(@PathVariable UUID id, @RequestBody(required = false) CateringEventService.ConfirmRequest r) { return events.confirm(id, r); }

    @PostMapping("/events/{id}/prepare")
    @PreAuthorize("@access.has('CATERING_MANAGE')")
    public CateringEventService.EventDto prepare(@PathVariable UUID id) { return events.startPreparation(id); }

    @PostMapping("/events/{id}/ready")
    @PreAuthorize("@access.has('CATERING_MANAGE')")
    public CateringEventService.EventDto ready(@PathVariable UUID id) { return events.ready(id); }

    @PostMapping("/events/{id}/complete")
    @PreAuthorize("@access.has('CATERING_MANAGE')")
    public CateringEventService.EventDto complete(@PathVariable UUID id) { return events.complete(id); }

    @PostMapping("/events/{id}/cancel")
    @PreAuthorize("@access.has('CATERING_MANAGE')")
    public CateringEventService.EventDto cancel(@PathVariable UUID id, @Valid @RequestBody CateringEventService.CancelRequest r) { return events.cancel(id, r); }

    @PostMapping("/events/{id}/payments")
    @PreAuthorize("@access.has('CATERING_MANAGE')")
    public CateringEventService.EventDto pay(@PathVariable UUID id, @Valid @RequestBody CateringEventService.PaymentRequest r) { return events.pay(id, r); }

    @PostMapping("/events/{id}/tasks")
    @PreAuthorize("@access.has('CATERING_MANAGE')")
    public CateringEventService.EventDto addTask(@PathVariable UUID id, @Valid @RequestBody CateringEventService.TaskRequest r) { return events.addTask(id, r); }

    @PutMapping("/events/{id}/tasks/{taskId}")
    @PreAuthorize("@access.has('CATERING_MANAGE') or @access.has('KITCHEN_STATUS_UPDATE')")
    public CateringEventService.EventDto setTask(@PathVariable UUID id, @PathVariable UUID taskId, @RequestBody CateringEventService.TaskDone d) { return events.setTask(id, taskId, d); }

    @PostMapping("/events/{id}/issue-stock")
    @PreAuthorize("@access.has('CATERING_MANAGE')")
    public CateringEventService.EventDto issue(@PathVariable UUID id) { return events.issueStock(id); }

    @PostMapping("/events/{id}/usage")
    @PreAuthorize("@access.has('CATERING_MANAGE') or @access.has('STOCK_WASTAGE')")
    public CateringEventService.EventDto usage(@PathVariable UUID id, @Valid @RequestBody CateringEventService.UsageRequest r) { return events.usage(id, r); }

    @PostMapping("/events/{id}/costs")
    @PreAuthorize("@access.has('CATERING_MANAGE')")
    public CateringEventService.EventDto addCost(@PathVariable UUID id, @Valid @RequestBody CateringEventService.CostRequest r) { return events.addCost(id, r); }

    @DeleteMapping("/events/{id}/costs/{costId}")
    @PreAuthorize("@access.has('CATERING_MANAGE')")
    public CateringEventService.EventDto removeCost(@PathVariable UUID id, @PathVariable UUID costId) { return events.removeEstimate(id, costId); }
}
