package com.amsati.shopscale.catalog;

import com.amsati.shopscale.catalog.CatalogDtos.*;
import com.amsati.shopscale.common.ApiException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Imports products from a CSV file by creating them through ProductService.create, so every rule that applies to a product added
 * by hand applies here too: the plan's product limit, validation, the opening-stock record and the audit entry. Each row is its
 * own transaction, so one bad row never blocks the others, and the result lists exactly which rows were not imported and why.
 */
@Service
@RequiredArgsConstructor
public class ProductImportService {
    public static final int MAX_ROWS = 500;
    public static final long MAX_BYTES = 1_000_000;

    public record RowError(int row, String name, String message) {}
    public record Result(int created, int failed, List<RowError> errors) {}

    private final ProductService products;

    public static final String TEMPLATE = "name,category,selling price,cost price,quantity,low stock level,sku,barcode\n"
        + "Sample product (delete this row),Provisions,1500,1100,20,5,,\n";

    private static String norm(String h) { return h == null ? "" : h.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", ""); }

    private static String field(List<String> row, Map<String, Integer> cols, String... names) {
        for (String n : names) {
            Integer i = cols.get(n);
            if (i != null && i < row.size()) { String v = row.get(i).trim(); if (!v.isEmpty()) return v; }
        }
        return null;
    }

    static BigDecimal number(String v, String what) {
        if (v == null) return null;
        String s = v.replace("₦", "").replace(",", "").replace(" ", "");
        try {
            BigDecimal n = new BigDecimal(s);
            if (n.signum() < 0) throw ApiException.badRequest(what + " cannot be negative");
            return n;
        } catch (NumberFormatException e) { throw ApiException.badRequest(what + " must be a number"); }
    }

    public Result importCsv(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw ApiException.badRequest("Choose a CSV file to import");
        if (bytes.length > MAX_BYTES) throw ApiException.badRequest("That file is too large. Import up to " + MAX_ROWS + " products at a time.");
        List<List<String>> rows = ProductCsv.parse(new String(bytes, StandardCharsets.UTF_8));
        if (rows.size() < 2) throw ApiException.badRequest("The file needs a header row and at least one product");
        if (rows.size() - 1 > MAX_ROWS) throw ApiException.badRequest("Import up to " + MAX_ROWS + " products at a time");
        Map<String, Integer> cols = new HashMap<>();
        List<String> head = rows.get(0);
        for (int i = 0; i < head.size(); i++) cols.putIfAbsent(norm(head.get(i)), i);
        if (!cols.containsKey("name") && !cols.containsKey("product") && !cols.containsKey("productname")) throw ApiException.badRequest("The first row must have a column called name");
        if (!cols.containsKey("sellingprice") && !cols.containsKey("price")) throw ApiException.badRequest("The first row must have a column called selling price");

        Map<String, UUID> categories = new HashMap<>();
        for (CategoryDto c : products.listCategories()) categories.put(c.name().toLowerCase(Locale.ROOT), c.id());

        int created = 0;
        List<RowError> errors = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String name = field(row, cols, "name", "product", "productname");
            try {
                if (name == null) throw ApiException.badRequest("Name is empty");
                if (name.length() > 200) throw ApiException.badRequest("Name is too long");
                BigDecimal price = number(field(row, cols, "sellingprice", "price"), "Selling price");
                if (price == null) throw ApiException.badRequest("Selling price is empty");
                BigDecimal cost = number(field(row, cols, "costprice", "cost", "buyingcost", "buyingprice"), "Cost price");
                BigDecimal qty = number(field(row, cols, "quantity", "stock", "openingstock", "qty"), "Quantity");
                BigDecimal low = number(field(row, cols, "lowstocklevel", "lowstock", "lowstockthreshold"), "Low stock level");
                boolean exists = products.list(name, null, false, 0, 10).getContent().stream().anyMatch(p -> p.name().equalsIgnoreCase(name));
                if (exists) throw ApiException.conflict("A product with this name already exists");
                UUID categoryId = null;
                String cat = field(row, cols, "category");
                if (cat != null) {
                    categoryId = categories.get(cat.toLowerCase(Locale.ROOT));
                    if (categoryId == null) {
                        categoryId = products.createCategory(new CategoryRequest(cat)).id();
                        categories.put(cat.toLowerCase(Locale.ROOT), categoryId);
                    }
                }
                VariantRequest v = new VariantRequest(null, null, field(row, cols, "sku"), field(row, cols, "barcode"), price, cost,
                    qty == null ? BigDecimal.ZERO : qty, low, null, null, null);
                products.create(new ProductRequest(name, null, categoryId, null, List.of(v), "GOODS"));
                created++;
            } catch (ApiException e) {
                errors.add(new RowError(r + 1, name == null ? "" : name, e.getMessage()));
            } catch (RuntimeException e) {
                errors.add(new RowError(r + 1, name == null ? "" : name, "Could not import this row"));
            }
        }
        return new Result(created, errors.size(), errors);
    }
}
