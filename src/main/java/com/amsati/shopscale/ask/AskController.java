package com.amsati.shopscale.ask;

import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Ask ShopScale. The business always comes from the signed-in session; the plan feature is enforced in AskService. */
@RestController
@RequestMapping("/api/ask")
@RequiredArgsConstructor
public class AskController {
    private final AskService service;

    @GetMapping("/questions")
    @PreAuthorize("@access.has('DASHBOARD_VIEW')")
    public List<AskService.Question> questions() { return service.questions(); }

    @GetMapping("/{key}")
    @PreAuthorize("@access.has('DASHBOARD_VIEW')")
    public AskService.Answer ask(@PathVariable String key, @RequestParam(defaultValue = "thisMonth") String range,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.ask(key, range, from, to);
    }
}
