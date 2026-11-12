package com.amsati.shopscale.subscription;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "subscription_payments")
@Getter @Setter @NoArgsConstructor
public class SubscriptionPayment {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private String planCode;
    @Enumerated(EnumType.STRING)
    private BillingCycle billingCycle;
    private String kind;
    private BigDecimal amount;
    private BigDecimal listPrice;
    private BigDecimal credit = BigDecimal.ZERO;
    private String currency = "NGN";
    private String reference;
    private String status = "PENDING";
    private UUID initiatedBy;
    private String paystackTransaction;
    private Instant paidAt;
    private Instant periodStart;
    private Instant periodEnd;
    private String failureNote;
    private String customerEmail;
    private boolean autoRenewConsent;
    private Instant consentAt;
    /** CHECKOUT = customer present; AUTO = charged by the renewal scheduler. */
    private String source = "CHECKOUT";
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    /** Copies what Paystack reported about the transaction that paid this row. */
    public void setPaymentDetails(com.amsati.shopscale.payment.PaystackClient.Verification v) {
        String id = v.raw() == null ? "" : v.raw().path("id").asText("");
        if (!id.isBlank()) this.paystackTransaction = id.length() > 40 ? id.substring(0, 40) : id;
    }
}
