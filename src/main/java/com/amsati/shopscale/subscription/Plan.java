package com.amsati.shopscale.subscription;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "plans")
@Getter @Setter @NoArgsConstructor
public class Plan {
    @Id
    @Column(name = "code")
    private String code;
    private String name;
    private BigDecimal monthlyPrice;
    private BigDecimal annualPrice;
    private int maxUsers;
    private int maxLocations;
    private Integer maxItems;
    private Integer maxCustomers;
    private Integer maxExpenses;
    private Integer maxSuppliers;
    private boolean recommended;
    private boolean active;
    private int sortOrder;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_features", joinColumns = @JoinColumn(name = "plan_code"))
    @Column(name = "feature")
    private Set<String> features = new HashSet<>();
}
