package com.amsati.shopscale.sales;

import com.amsati.shopscale.common.PaymentMethod;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "sale_payments")
@Getter @Setter @NoArgsConstructor
public class SalePayment {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_id")
    private Sale sale;
    private UUID businessId;
    @Enumerated(EnumType.STRING)
    private PaymentMethod method;
    private BigDecimal amount;
}
