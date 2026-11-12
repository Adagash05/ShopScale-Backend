package com.amsati.shopscale.invoice;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "invoice_settings")
@Getter @Setter @NoArgsConstructor
public class InvoiceSettings {
    @Id
    private UUID businessId;
    private String paymentInstructions;
    private String defaultTerms;
    private BigDecimal defaultTaxRate = BigDecimal.ZERO;
    private byte[] logo;
    private String logoType;
}
