package com.library.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
public class InventoryReconciliation {
    private LocalDate asOfDate;
    private Map<String, String> statisticalScope;
    private long totalCopies;
    private long availableCopies;
    private long borrowedCopies;
    private long registeredCopies;
    private long registeredAvailableCopies;
    private long registeredBorrowedCopies;
    private long registeredOtherCopies;
    private long mismatchedTitles;
    private List<Item> items;
    private com.library.service.CopyInventoryProjection.V2 reconciliationV2;

    @Data
    public static class Item {
        private Long bookId;
        private String title;
        private long totalCopies;
        private long availableCopies;
        private long borrowedCopies;
        private long registeredCopies;
        private long registeredAvailableCopies;
        private long registeredBorrowedCopies;
        private long registeredOtherCopies;
        private com.library.service.CopyInventoryProjection.V2 reconciliationV2;

        public long getTotalDifference() { return totalCopies - registeredCopies; }
        public long getAvailableDifference() { return availableCopies - registeredAvailableCopies; }
        public long getBorrowedDifference() { return borrowedCopies - registeredBorrowedCopies; }
        public long getStockBalanceDifference() { return totalCopies - availableCopies - borrowedCopies; }
        public boolean isConsistent() {
            return getTotalDifference() == 0 && getAvailableDifference() == 0
                    && getBorrowedDifference() == 0 && getStockBalanceDifference() == 0
                    && registeredOtherCopies == 0;
        }
    }
}
