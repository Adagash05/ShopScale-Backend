package com.amsati.shopscale.catalog;

import java.util.ArrayList;
import java.util.List;

/** Small, dependency-free CSV reader for product imports: quoted fields, doubled quotes, commas and line breaks inside quotes, CRLF, a leading BOM. */
public final class ProductCsv {
    private ProductCsv() {}

    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null) return rows;
        if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false, any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; } else quoted = false;
                } else cell.append(c);
            } else if (c == '"') { quoted = true; any = true; }
            else if (c == ',') { row.add(cell.toString()); cell.setLength(0); any = true; }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                if (any || cell.length() > 0) { row.add(cell.toString()); rows.add(row); }
                row = new ArrayList<>(); cell.setLength(0); any = false;
            } else { cell.append(c); any = true; }
        }
        if (any || cell.length() > 0) { row.add(cell.toString()); rows.add(row); }
        return rows;
    }
}
