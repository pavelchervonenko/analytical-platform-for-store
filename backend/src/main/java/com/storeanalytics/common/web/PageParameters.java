package com.storeanalytics.common.web;

import com.storeanalytics.common.exception.InvalidRequestException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public record PageParameters(int page, int size) {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    public static final int MAX_PAGE = 10_000;

    public PageParameters {
        if (page < 0 || page > MAX_PAGE) {
            throw new InvalidRequestException(
                    "page must be between 0 and " + MAX_PAGE
            );
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidRequestException(
                    "size must be between 1 and " + MAX_SIZE
            );
        }
    }

    public Pageable pageable(Sort sort) {
        return PageRequest.of(page, size, sort);
    }
}
