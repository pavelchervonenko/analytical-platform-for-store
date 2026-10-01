package com.storeanalytics.product.web;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/catalog-product-reviews/categories")
public class CatalogReviewCategoriesController {
    private final JdbcTemplate jdbc;

    public CatalogReviewCategoriesController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    List<CategoryOption> list() {
        return jdbc.query("""
                SELECT code, name, payroll_category_code
                FROM analytics_categories
                WHERE is_active AND code <> 'UNMAPPED'
                ORDER BY category_kind, name, code
                """, (row, index) -> new CategoryOption(row.getString("code"),
                row.getString("name"), row.getString("payroll_category_code")));
    }

    record CategoryOption(String code, String name, String defaultPayrollCategoryCode) { }
}
