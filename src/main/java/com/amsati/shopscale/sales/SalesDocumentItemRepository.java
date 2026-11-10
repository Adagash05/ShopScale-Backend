package com.amsati.shopscale.sales;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesDocumentItemRepository extends JpaRepository<SalesDocumentItem, UUID> {
    List<SalesDocumentItem> findByDocumentId(UUID documentId);
}
