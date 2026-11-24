package com.amsati.shopscale.reminder;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** WhatsApp reminder allowance for the signed-in business. The business and the plan always come from the session, never from the request. */
@RestController
@RequestMapping("/api/reminders")
@RequiredArgsConstructor
public class ReminderController {
    private final ReminderService service;

    @GetMapping("/usage")
    @PreAuthorize("@access.has('CUSTOMER_INVOICES_VIEW') or @access.has('CUSTOMER_BALANCES_VIEW')")
    public ReminderService.Usage usage() { return service.usage(); }

    @PostMapping
    @PreAuthorize("@access.has('CUSTOMER_INVOICES_VIEW') or @access.has('CUSTOMER_BALANCES_VIEW')")
    public ReminderService.Recorded record(@Valid @RequestBody ReminderService.RecordRequest r) { return service.record(r); }
}
