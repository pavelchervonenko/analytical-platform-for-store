package com.storeanalytics.product.web;

import com.storeanalytics.product.service.ProductCategoryImportCommand;
import com.storeanalytics.product.service.ProductCategoryImportEntry;
import com.storeanalytics.product.service.ProductCategoryImportResult;
import com.storeanalytics.product.service.ProductCategoryImportService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integration-connections/{connectionKey}/product-category-imports")
public class ProductCategoryImportController {

    private final ProductCategoryImportService importService;

    public ProductCategoryImportController(ProductCategoryImportService importService) {
        this.importService = importService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    ProductCategoryImportResult importAssignments(
            @PathVariable String connectionKey,
            @Valid @RequestBody ProductCategoryImportRequest request
    ) {
        return importService.importAssignments(new ProductCategoryImportCommand(
                connectionKey,
                request.validFrom(),
                request.ruleVersion(),
                request.changeReason(),
                request.assignments().stream()
                        .map(item -> new ProductCategoryImportEntry(
                                item.externalProductId(),
                                item.productName(),
                                item.categoryCode(),
                                item.conditionType()
                        ))
                        .toList()
        ));
    }
}
