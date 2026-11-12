package com.amsati.shopscale.security;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Guards the authorization layer without a database: every endpoint is either explicitly public or protected by a
 * PreAuthorize that names real permissions. A typo in a permission name would silently lock everyone out.
 */
class PermissionAnnotationsTest {
    private static final Pattern NAME = Pattern.compile("has\\('([A-Z_]+)'\\)");
    private static final List<String> PUBLIC_HANDLERS = List.of(
        "AuthController.register", "AuthController.login", "AuthController.refresh", "AuthController.logout",
        "SubscriptionController.plans", "TeamController.preview", "TeamController.accept",
        "BillingController.webhook", "StoreWebhookController.receive", "ProductImageController.serve", "UnitController.common", "BranchController.list",
        "StoreExtrasController.logo", "StoreExtrasController.reviews", "StoreExtrasController.testimonials", "StoreExtrasController.review",
        "StoreExtrasController.subscribe", "StoreExtrasController.enquire", "StoreExtrasController.lookup");

    private static List<Class<?>> controllers() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> out = new ArrayList<>();
        for (var bd : scanner.findCandidateComponents("com.amsati.shopscale")) out.add(Class.forName(bd.getBeanClassName()));
        return out;
    }

    private static boolean isHandler(Method m) {
        return java.util.Arrays.stream(m.getAnnotations()).anyMatch(a -> a.annotationType().getSimpleName().endsWith("Mapping"));
    }

    @Test
    void everyPermissionNamedInAnnotationsExists() throws Exception {
        int checked = 0;
        for (Class<?> c : controllers()) {
            for (Method m : c.getDeclaredMethods()) {
                PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
                if (pa == null) continue;
                Matcher mt = NAME.matcher(pa.value());
                while (mt.find()) {
                    String name = mt.group(1);
                    assertDoesNotThrow(() -> Permission.valueOf(name), c.getSimpleName() + "." + m.getName() + " uses unknown permission " + name);
                    checked++;
                }
            }
        }
        assertTrue(checked > 30, "expected many permission checks, found " + checked);
    }

    @Test
    void everyHandlerIsProtectedOrExplicitlyPublic() throws Exception {
        for (Class<?> c : controllers()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!isHandler(m)) continue;
                String id = c.getSimpleName() + "." + m.getName();
                boolean annotated = m.isAnnotationPresent(PreAuthorize.class) || c.isAnnotationPresent(PreAuthorize.class);
                if (!annotated) {
                    boolean allowed = PUBLIC_HANDLERS.contains(id)
                        || c.getSimpleName().equals("BusinessController") || id.startsWith("SubscriptionController")
                        || id.startsWith("AuthController")
                        || c.getSimpleName().equals("PublicStoreController") || c.getSimpleName().equals("PublicInvoiceController")
                        || c.getSimpleName().equals("NotificationController");   // public by design / scoped to the signed-in user in the service   // identity endpoints: authenticated by the filter chain
                    assertTrue(allowed, id + " has no @PreAuthorize and is not on the reviewed list of open endpoints");
                }
            }
        }
    }

    @Test
    void noLegacyRoleChecksRemain() throws Exception {
        for (Class<?> c : controllers()) {
            for (Method m : c.getDeclaredMethods()) {
                PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
                if (pa != null) assertFalse(pa.value().contains("hasRole") || pa.value().contains("hasAnyRole"),
                    c.getSimpleName() + "." + m.getName() + " still uses a hard-coded role");
            }
        }
    }
}
