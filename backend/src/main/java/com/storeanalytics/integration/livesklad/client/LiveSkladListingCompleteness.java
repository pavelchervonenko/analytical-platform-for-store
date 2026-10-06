package com.storeanalytics.integration.livesklad.client;

import com.storeanalytics.integration.livesklad.exception.LiveSkladException;
import java.util.Objects;

/** One request sequence: declared totals must stay stable and equal the terminal received count. */
final class LiveSkladListingCompleteness {
    private final String collection;
    private final int pageSize;
    private final boolean requireDeclaredTotal;
    private boolean first = true;
    private Integer expectedTotal;
    private int received;

    LiveSkladListingCompleteness(String collection, int pageSize, boolean requireDeclaredTotal) {
        this.collection = collection;
        this.pageSize = pageSize;
        this.requireDeclaredTotal = requireDeclaredTotal;
    }

    boolean acceptPage(int rows, Integer total) {
        if (first) {
            expectedTotal = total;
            first = false;
        } else if (!Objects.equals(expectedTotal, total)) {
            throw new LiveSkladException("LiveSklad " + collection + " declared total changed during pagination");
        }
        if (expectedTotal != null && expectedTotal < 0) {
            throw new LiveSkladException("LiveSklad " + collection + " declared total is invalid");
        }
        if (expectedTotal == null && requireDeclaredTotal) {
            throw new LiveSkladException("Historical SALE refresh requires a declared listing total");
        }
        received += rows;
        if (expectedTotal != null && received > expectedTotal) {
            throw new LiveSkladException("LiveSklad " + collection + " exceed the declared listing total");
        }
        boolean terminal = rows < pageSize || expectedTotal != null && received == expectedTotal;
        if (terminal && expectedTotal != null && received != expectedTotal) {
            throw new LiveSkladException("LiveSklad " + collection + " terminal page is incomplete");
        }
        // The all-pages-no-total legacy contract is short-page termination, not independent source completeness.
        return terminal;
    }
}
