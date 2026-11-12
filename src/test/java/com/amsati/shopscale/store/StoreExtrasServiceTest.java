package com.amsati.shopscale.store;

import static org.junit.jupiter.api.Assertions.*;

import com.amsati.shopscale.common.ApiException;
import org.junit.jupiter.api.Test;

/** Pure-logic checks for the storefront extras (no Spring context, no database). */
class StoreExtrasServiceTest {
    @Test void socialLinksAcceptOnlyTheNetworksOwnDomain() {
        assertEquals("https://instagram.com/ada", StoreExtrasService.social("instagram.com/ada", "instagram.com"));
        assertEquals("https://www.instagram.com/ada", StoreExtrasService.social("https://www.instagram.com/ada", "instagram.com"));
        assertNull(StoreExtrasService.social("  ", "instagram.com"));
        assertThrows(ApiException.class, () -> StoreExtrasService.social("https://evil.example/instagram.com", "instagram.com"));
        assertThrows(ApiException.class, () -> StoreExtrasService.social("https://instagram.com.evil.example/a", "instagram.com"));
        assertThrows(ApiException.class, () -> StoreExtrasService.social("javascript:alert(1)", "instagram.com"));
    }

    @Test void specsAreNormalisedToLabelValueLines() {
        assertEquals("Material: Cotton\nFit: Slim: relaxed", StoreExtrasService.parseSpecs(" Material :Cotton \n\nFit: Slim: relaxed"));
        assertNull(StoreExtrasService.parseSpecs("  \n "));
        assertThrows(ApiException.class, () -> StoreExtrasService.parseSpecs("no colon here"));
        assertThrows(ApiException.class, () -> StoreExtrasService.parseSpecs("Label:"));
        assertThrows(ApiException.class, () -> StoreExtrasService.parseSpecs("a: b\n".repeat(21)));
    }

    @Test void orderLookupMatchesPhonesIgnoringFormatting() {
        assertTrue(StoreExtrasService.sameLine("08012345678", "0801 234 5678"));
        assertTrue(StoreExtrasService.sameLine("+2348012345678", "08012345678"));
        assertFalse(StoreExtrasService.sameLine("08012345678", "08099998888"));
        assertFalse(StoreExtrasService.sameLine("08012345678", "123"));
        assertFalse(StoreExtrasService.sameLine(null, "08012345678"));
    }
}
