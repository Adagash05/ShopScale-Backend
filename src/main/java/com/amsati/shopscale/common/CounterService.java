package com.amsati.shopscale.common;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Gap-free per-business numbering (orders, events, batches). Atomic in the database, safe under concurrency. */
@Service
public class CounterService {
    @PersistenceContext
    private EntityManager em;

    @Transactional
    public long next(UUID businessId, String name) {
        Object v = em.createNativeQuery("INSERT INTO business_counters (business_id, name, value) VALUES (:b, :n, 1) "
                + "ON CONFLICT (business_id, name) DO UPDATE SET value = business_counters.value + 1 RETURNING value")
            .setParameter("b", businessId).setParameter("n", name).getSingleResult();
        return ((Number) v).longValue();
    }
}
