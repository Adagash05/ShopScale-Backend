package com.amsati.shopscale.branch;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.catalog.ProductVariant;
import com.amsati.shopscale.catalog.ProductVariantRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.subscription.SubscriptionService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BranchService {
    private final BranchRepository branches;
    private final BranchStockRepository branchStock;
    private final ProductVariantRepository variants;
    private final SubscriptionService subscriptions;
    private final AuditService audit;

    public record BranchRequest(@NotBlank @Size(max = 100) String name, @Size(max = 500) String address, @Size(max = 40) String phone) {}
    public record BranchDto(UUID id, String name, String address, String phone, boolean main, boolean active) {}
    public record VariantBranchStock(UUID branchId, String branchName, BigDecimal quantity) {}

    @Transactional(readOnly = true)
    public List<BranchDto> list() {
        return branches.findByBusinessIdOrderByMainDescNameAsc(Tenant.businessId()).stream().map(BranchService::dto).toList();
    }

    @Transactional
    public BranchDto create(BranchRequest r) {
        UUID bid = Tenant.businessId();
        subscriptions.requireWritable(bid);
        String name = r.name().trim();
        if (branches.existsByBusinessIdAndNameIgnoreCase(bid, name)) throw ApiException.conflict("You already have a branch with that name");
        subscriptions.requireLocationCapacity(bid, branches.countByBusinessIdAndActiveTrue(bid) + 1);
        Branch b = new Branch();
        b.setBusinessId(bid);
        b.setName(name);
        b.setAddress(blank(r.address()));
        b.setPhone(blank(r.phone()));
        branches.save(b);
        audit.record(bid, Tenant.userId(), "BRANCH_CREATED", "branch", b.getId(), name);
        return dto(b);
    }

    @Transactional
    public BranchDto update(UUID id, BranchRequest r) {
        UUID bid = Tenant.businessId();
        Branch b = branches.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Branch not found"));
        String name = r.name().trim();
        if (!b.getName().equalsIgnoreCase(name) && branches.existsByBusinessIdAndNameIgnoreCase(bid, name)) {
            throw ApiException.conflict("You already have a branch with that name");
        }
        b.setName(name);
        b.setAddress(blank(r.address()));
        b.setPhone(blank(r.phone()));
        b.setUpdatedAt(Instant.now());
        audit.record(bid, Tenant.userId(), "BRANCH_UPDATED", "branch", id, name);
        return dto(b);
    }

    @Transactional
    public BranchDto setActive(UUID id, boolean active) {
        UUID bid = Tenant.businessId();
        Branch b = branches.findByIdAndBusinessId(id, bid).orElseThrow(() -> ApiException.notFound("Branch not found"));
        if (b.isActive() == active) return dto(b);
        if (!active) {
            if (b.isMain()) throw ApiException.badRequest("The main branch cannot be switched off");
            if (branchStock.unitsAtBranch(bid, id).signum() != 0) {
                throw ApiException.conflict("This branch still has stock. Transfer or adjust it to zero first.");
            }
        } else {
            subscriptions.requireLocationCapacity(bid, branches.countByBusinessIdAndActiveTrue(bid) + 1);
        }
        b.setActive(active);
        b.setUpdatedAt(Instant.now());
        audit.record(bid, Tenant.userId(), active ? "BRANCH_ACTIVATED" : "BRANCH_DEACTIVATED", "branch", id, b.getName());
        return dto(b);
    }

    /** Where one product's stock sits across branches. */
    @Transactional(readOnly = true)
    public List<VariantBranchStock> stockOfVariant(UUID variantId) {
        UUID bid = Tenant.businessId();
        ProductVariant v = variants.findById(variantId).filter(x -> x.getBusinessId().equals(bid))
            .orElseThrow(() -> ApiException.notFound("Product variant not found"));
        Map<UUID, Branch> byId = branches.findByBusinessIdOrderByMainDescNameAsc(bid).stream().collect(Collectors.toMap(Branch::getId, Function.identity()));
        return branchStock.forVariant(bid, v.getId()).stream()
            .filter(s -> byId.containsKey(s.getId().getBranchId()))
            .map(s -> new VariantBranchStock(s.getId().getBranchId(), byId.get(s.getId().getBranchId()).getName(), s.getQuantity()))
            .sorted((a, b) -> a.branchName().compareToIgnoreCase(b.branchName())).toList();
    }

    private static BranchDto dto(Branch b) { return new BranchDto(b.getId(), b.getName(), b.getAddress(), b.getPhone(), b.isMain(), b.isActive()); }
    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
