package com.amsati.shopscale.store;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "store_logos")
@Getter @Setter @NoArgsConstructor
public class StoreLogo {
    @Id
    private UUID businessId;
    private byte[] data;
}
