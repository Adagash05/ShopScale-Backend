package com.amsati.shopscale.sales;

import com.amsati.shopscale.auth.User;
import com.amsati.shopscale.auth.UserRepository;
import com.amsati.shopscale.branch.Branch;
import com.amsati.shopscale.branch.BranchRepository;
import com.amsati.shopscale.business.Business;
import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.DateRange;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.security.Tenant;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sales broken down by employee, branch, payment method or sales channel. Returns are already taken off. */
@Service
@RequiredArgsConstructor
public class SalesReportService {
    private final SalesReportRepository repo;
    private final BusinessRepository businesses;
    private final UserRepository users;
    private final BranchRepository branches;

    public record Line(String key, String label, long count, BigDecimal total, BigDecimal profit) {}
    public record Report(String groupBy, LocalDate from, LocalDate to, BigDecimal total, List<Line> lines) {}

    @Transactional(readOnly = true)
    public Report report(String groupBy, String range, LocalDate from, LocalDate to, boolean includeProfit) {
        UUID bid = Tenant.businessId();
        Business b = businesses.findById(bid).orElseThrow();
        DateRange r = DateRange.resolve(range, from, to, LocalDate.now(b.zone()));
        var start = r.start(b.zone());
        var end = r.endExclusive(b.zone());
        List<SalesReportRepository.Row> rows = switch (groupBy) {
            case "employee" -> repo.byEmployee(bid, start, end);
            case "branch" -> repo.byBranch(bid, start, end);
            case "channel" -> repo.byChannel(bid, start, end);
            case "method" -> repo.byMethod(bid, start, end);
            default -> throw ApiException.badRequest("Choose employee, branch, method or channel");
        };
        Map<String, String> names = new HashMap<>();
        if (groupBy.equals("employee")) {
            users.findAllById(rows.stream().map(x -> (UUID) x.getKey()).filter(Objects::nonNull).collect(Collectors.toSet())).forEach(u -> names.put(u.getId().toString(), u.getFullName()));
        } else if (groupBy.equals("branch")) {
            branches.findByBusinessIdOrderByMainDescNameAsc(bid).forEach(x -> names.put(x.getId().toString(), x.getName()));
        }
        BigDecimal sum = BigDecimal.ZERO;
        List<Line> lines = new ArrayList<>();
        for (SalesReportRepository.Row x : rows) {
            String key = x.getKey() == null ? "none" : x.getKey().toString();
            BigDecimal total = x.getTotal() == null ? BigDecimal.ZERO : x.getTotal();
            BigDecimal cost = x.getCost() == null ? BigDecimal.ZERO : x.getCost();
            lines.add(new Line(key, names.getOrDefault(key, key.equals("none") ? "Unassigned" : key), x.getCount() == null ? 0 : x.getCount(),
                Money.scale(total), includeProfit && !groupBy.equals("method") ? Money.scale(total.subtract(cost)) : null));
            sum = sum.add(total);
        }
        return new Report(groupBy, r.from(), r.to(), Money.scale(sum), lines);
    }
}
