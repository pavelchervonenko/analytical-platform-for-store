package com.storeanalytics.product.web;

import com.storeanalytics.product.service.CatalogProductReviewQueue;
import com.storeanalytics.product.service.CatalogProductReviewQueueService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/catalog-product-reviews")
public class CatalogProductReviewController {
    private final CatalogProductReviewQueueService queue;

    public CatalogProductReviewController(CatalogProductReviewQueueService queue) {
        this.queue = queue;
    }

    @GetMapping
    CatalogProductReviewQueue list(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        return queue.list(limit);
    }
}
