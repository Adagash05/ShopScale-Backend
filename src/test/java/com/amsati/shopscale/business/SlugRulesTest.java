package com.amsati.shopscale.business;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SlugRulesTest {
    @Test
    void acceptsSafeSlugs() {
        assertTrue(SlugRules.isValidFormat("fashionhouse"));
        assertTrue(SlugRules.isValidFormat("beauty-hub-2"));
        assertTrue(SlugRules.isValidFormat("abc"));
    }

    @Test
    void rejectsUnsafeSlugs() {
        assertFalse(SlugRules.isValidFormat("ab"));
        assertFalse(SlugRules.isValidFormat("-shop"));
        assertFalse(SlugRules.isValidFormat("shop-"));
        assertFalse(SlugRules.isValidFormat("my shop"));
        assertFalse(SlugRules.isValidFormat("my_shop"));
        assertFalse(SlugRules.isValidFormat("shop--now"));
        assertFalse(SlugRules.isValidFormat("../etc"));
        assertFalse(SlugRules.isValidFormat("a".repeat(41)));
    }

    @Test
    void normalizesAndReservesAppRoutes() {
        assertEquals("fashionhouse", SlugRules.normalize("  FashionHouse "));
        assertTrue(SlugRules.isReserved("admin"));
        assertTrue(SlugRules.isReserved("api"));
        assertFalse(SlugRules.isReserved("fashionhouse"));
    }
}
