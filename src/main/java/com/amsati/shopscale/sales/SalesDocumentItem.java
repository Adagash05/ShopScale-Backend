package com.amsati.shopscale.sales;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "sales_document_items")
@Getter @Setter @NoArgsConstructor
public class SalesDocumentItem {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID documentId;
    private UUID businessId;
    private UUID variantId;
    private String productName;
    private String variantLabel;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private BigDecimal lineTotal;
}
