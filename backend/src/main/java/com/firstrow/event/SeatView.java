package com.firstrow.event;

/**
 * One seat on the seat map. {@code status} is the EFFECTIVE status:
 * a seat whose hold has expired is reported as AVAILABLE even if the cleanup job hasn't run yet.
 */
public record SeatView(long id,
                       String section,
                       String row,
                       int number,
                       String priceTier,
                       long pricePaise,
                       String status) {
}
