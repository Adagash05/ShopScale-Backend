package com.amsati.shopscale.transfer;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.branch.Branch;
import com.amsati.shopscale.branch.BranchRepository;
import com.amsati.shopscale.business.Business;
import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.catalog.ProductVariant;
import com.amsati.shopscale.catalog.ProductVariantRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.inventory.MovementType;
import com.amsati.shopscale.inventory.StockService;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.subscription.SubscriptionService;
import com.amsati.shopscale.unit.UnitService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves stock between branches. Sending takes stock out of the source branch straight away (it is "in transit" and
 * counted nowhere). Receiving puts it into the destination. Cancelling an in-transit transfer returns it to the source.
 */
@Service
@RequiredArgsConstructor
public class TransferService {
    public static final String IN_TRANSIT = "IN_TRANSIT", RECEIVED = "RECEIVED", CANCELLED = "CANCELLED";

    private final StockTransferRepository transfers;
    private final StockTransferItemRepository items;
    private final BusinessRepository businesses;
    private final BranchRepository branches;
    private final ProductVariantRepository variants;
    private final StockService stock;
    private final UnitService units;
    private final SubscriptionService subscriptions;
    private final AuditService audit;

    public record ItemRequest(@NotNull UUID variantId, @NotNull @DecimalMin(value = "0.001", message = "Quantity must be greater than zero") BigDecimal quantity) {}
    public record TransferRequest(@NotNull UUID fromBranchId, @NotNull UUID toBranchId,
                                  @NotEmpty(message = "Add at least one product") @Valid List<ItemRequest> items,
                                  @Size(max = 300) String note, @Size(max = 64) String clientRequestId) {}
    public record ItemDto(UUID variantId, String productName, String variantLabel, BigDecimal quantity) {}
    public record TransferDto(UUID id, long transferNumber, UUID fromBranchId, String fromBranch, UUID toBranchId, String toBranch,
                              String status, String note, Instant createdAt, Instant receivedAt, List<ItemDto> items) {}

    @Transactional
    public TransferDto create(TransferRequest r) {
        UUID bid = Tenant.businessId();
        UUID uid = Tenant.userId();
        subscriptions.requireWritable(bid);
        subscriptions.requireFeature(bid, "BRANCH_TRANSFERS", "Transferring stock between branches");
        String requestId = r.clientRequestId() == null || r.clientRequestId().isBlank() ? null : r.clientRequestId();
        if (requestId != null) {
            Optional<StockTransfer> prior = transfers.findByBusinessIdAndClientRequestId(bid, requestId);
            if (prior.isPresent()) return toDto(prior.get());
        }
        if (r.fromBranchId().equals(r.toBranchId())) throw ApiException.badRequest("Choose two different branches");
        UUID from = stock.resolveBranch(bid, r.fromBranchId());
        UUID to = stock.resolveBranch(bid, r.toBranchId());

        Set<UUID> ids = new LinkedHashSet<>();
        for (ItemRequest i : r.items()) if (!ids.add(i.variantId())) throw ApiException.badRequest("List each product once. Add the quantities together.");
        Business business = businesses.findByIdForUpdate(bid).orElseThrow(() -> ApiException.notFound("Business not found"));
        Map<UUID, ProductVariant> byId = variants.lockAll(bid, ids).stream().collect(Collectors.toMap(ProductVariant::getId, v -> v));
        if (byId.size() != ids.size()) throw ApiException.badRequest("One of the products no longer exists");

        business.setTransferCounter(business.getTransferCounter() + 1);
        StockTransfer t = new StockTransfer();
        t.setBusinessId(bid);
        t.setTransferNumber(business.getTransferCounter());
        t.setFromBranchId(from);
        t.setToBranchId(to);
        t.setStatus(IN_TRANSIT);
        t.setNote(blank(r.note()));
        t.setClientRequestId(requestId);
        t.setCreatedBy(uid);
        transfers.save(t);

        String ref = "TRANSFER-" + t.getTransferNumber();
        for (ItemRequest i : r.items()) {
            ProductVariant v = byId.get(i.variantId());
            units.requireValidQuantity(v, i.quantity());
            // Never allow a transfer to take a branch below zero, whatever the negative-stock setting is.
            stock.apply(v, from, MovementType.TRANSFER_OUT, i.quantity().negate(), "Sent to another branch", ref, uid, false);
            StockTransferItem it = new StockTransferItem();
            it.setTransferId(t.getId());
            it.setBusinessId(bid);
            it.setVariantId(v.getId());
            it.setQuantity(i.quantity());
            items.save(it);
        }
        audit.record(bid, uid, "TRANSFER_SENT", "transfer", t.getId(), ref);
        return toDto(t);
    }

    @Transactional
    public TransferDto receive(UUID id) {
        UUID bid = Tenant.businessId();
        UUID uid = Tenant.userId();
        subscriptions.requireWritable(bid);
        StockTransfer t = transfers.findForUpdate(id, bid).orElseThrow(() -> ApiException.notFound("Transfer not found"));
        if (!IN_TRANSIT.equals(t.getStatus())) throw ApiException.conflict("This transfer is already " + t.getStatus().toLowerCase().replace('_', ' '));
        List<StockTransferItem> lines = items.findByTransferId(t.getId());
        Map<UUID, ProductVariant> byId = variants.lockAll(bid, lines.stream().map(StockTransferItem::getVariantId).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(ProductVariant::getId, v -> v));
        String ref = "TRANSFER-" + t.getTransferNumber();
        for (StockTransferItem it : lines) {
            ProductVariant v = byId.get(it.getVariantId());
            stock.apply(v, t.getToBranchId(), MovementType.TRANSFER_IN, it.getQuantity(), "Received from another branch", ref, uid, true);
            it.setQuantityReceived(it.getQuantity());
        }
        t.setStatus(RECEIVED);
        t.setReceivedBy(uid);
        t.setReceivedAt(Instant.now());
        audit.record(bid, uid, "TRANSFER_RECEIVED", "transfer", t.getId(), ref);
        return toDto(t);
    }

    @Transactional
    public TransferDto cancel(UUID id) {
        UUID bid = Tenant.businessId();
        UUID uid = Tenant.userId();
        subscriptions.requireWritable(bid);
        StockTransfer t = transfers.findForUpdate(id, bid).orElseThrow(() -> ApiException.notFound("Transfer not found"));
        if (!IN_TRANSIT.equals(t.getStatus())) throw ApiException.conflict("Only a transfer that is still on the way can be cancelled");
        List<StockTransferItem> lines = items.findByTransferId(t.getId());
        Map<UUID, ProductVariant> byId = variants.lockAll(bid, lines.stream().map(StockTransferItem::getVariantId).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(ProductVariant::getId, v -> v));
        String ref = "TRANSFER-" + t.getTransferNumber();
        for (StockTransferItem it : lines) {
            stock.apply(byId.get(it.getVariantId()), t.getFromBranchId(), MovementType.TRANSFER_IN, it.getQuantity(), "Transfer cancelled", ref, uid, true);
        }
        t.setStatus(CANCELLED);
        audit.record(bid, uid, "TRANSFER_CANCELLED", "transfer", t.getId(), ref);
        return toDto(t);
    }

    @Transactional(readOnly = true)
    public Page<TransferDto> list(int page, int size) {
        return transfers.findByBusinessIdOrderByCreatedAtDesc(Tenant.businessId(), PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)))
            .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public TransferDto get(UUID id) {
        return toDto(transfers.findByIdAndBusinessId(id, Tenant.businessId()).orElseThrow(() -> ApiException.notFound("Transfer not found")));
    }

    private TransferDto toDto(StockTransfer t) {
        Map<UUID, String> names = branches.findByBusinessIdOrderByMainDescNameAsc(t.getBusinessId()).stream().collect(Collectors.toMap(Branch::getId, Branch::getName));
        List<StockTransferItem> lines = items.findByTransferId(t.getId());
        Map<UUID, ProductVariant> vs = variants.findAllById(lines.stream().map(StockTransferItem::getVariantId).toList())
            .stream().collect(Collectors.toMap(ProductVariant::getId, v -> v));
        List<ItemDto> dtos = lines.stream().map(i -> {
            ProductVariant v = vs.get(i.getVariantId());
            return new ItemDto(i.getVariantId(), v.getProduct().getName(), v.getLabel(), i.getQuantity());
        }).toList();
        return new TransferDto(t.getId(), t.getTransferNumber(), t.getFromBranchId(), names.get(t.getFromBranchId()), t.getToBranchId(),
            names.get(t.getToBranchId()), t.getStatus(), t.getNote(), t.getCreatedAt(), t.getReceivedAt(), dtos);
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
