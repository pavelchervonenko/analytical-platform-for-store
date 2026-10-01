package com.storeanalytics.common.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Synthetic-fixture fingerprints include every column, not only category or monetary amounts. */
final class HistoricalCatalogRows {
    private HistoricalCatalogRows() { }

    static Map<String, String> snapshot(Connection connection) throws SQLException {
        var result = new LinkedHashMap<String, String>();
        for (String table : List.of("products", "sales_documents", "sales_document_items",
                "product_category_assignments", "product_payroll_category_assignments")) {
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT count(*)::text || ':' || "
                         + "md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id)::text, '[]')) FROM "
                         + table + " t")) {
                rows.next();
                result.put(table, rows.getString(1));
            }
        }
        return result;
    }
}
