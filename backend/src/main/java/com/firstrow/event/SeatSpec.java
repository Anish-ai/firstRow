package com.firstrow.event;

/** A seat to be created for a new event (output of {@link SeatLayoutGenerator}). */
public record SeatSpec(String section, String row, int number, String priceTier, long pricePaise) {
}
