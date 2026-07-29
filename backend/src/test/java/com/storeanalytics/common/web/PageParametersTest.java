package com.storeanalytics.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.common.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class PageParametersTest {

    @Test
    void createsBoundedPageableAtSupportedLimits() {
        var pageable = new PageParameters(
                PageParameters.MAX_PAGE,
                PageParameters.MAX_SIZE
        ).pageable(Sort.by(Sort.Direction.DESC, "id"));

        assertThat(pageable.getPageNumber()).isEqualTo(PageParameters.MAX_PAGE);
        assertThat(pageable.getPageSize()).isEqualTo(PageParameters.MAX_SIZE);
        assertThat(pageable.getSort().getOrderFor("id").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void rejectsNegativeOrExcessivePage() {
        assertThatThrownBy(() -> new PageParameters(-1, 20))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("page must be between");
        assertThatThrownBy(() -> new PageParameters(PageParameters.MAX_PAGE + 1, 20))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("page must be between");
    }

    @Test
    void rejectsZeroOrExcessiveSize() {
        assertThatThrownBy(() -> new PageParameters(0, 0))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("size must be between");
        assertThatThrownBy(() -> new PageParameters(0, PageParameters.MAX_SIZE + 1))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("size must be between");
    }
}
