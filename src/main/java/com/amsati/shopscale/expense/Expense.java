package com.amsati.shopscale.expense;

import com.amsati.shopscale.common.PaymentMethod;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "expenses")
@Getter @Setter @NoArgsConstructor
public class Expense {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID businessId;
    private String category;
    private BigDecimal amount;
    private LocalDate expenseDate;
    private String description;
    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;
    private UUID createdBy;
    private UUID branchId;
    private String status = "APPROVED";
    private UUID decidedBy;
    private Instant decidedAt;
    private String decisionNote;
    private Instant createdAt = Instant.now();
}
