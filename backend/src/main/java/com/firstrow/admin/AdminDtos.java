package com.firstrow.admin;

import com.firstrow.event.EventType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Request bodies for the admin API. Bean Validation annotations reject bad input with a 400. */
public final class AdminDtos {

    private AdminDtos() {
    }

    public record CreateVenueRequest(@NotBlank @Size(max = 200) String name,
                                     @NotBlank @Size(max = 100) String city) {
    }

    public record CreateEventRequest(@NotNull Long venueId,
                                     @NotBlank @Size(max = 200) String title,
                                     @NotNull EventType type,
                                     @NotNull @Future Instant startsAt,
                                     @NotNull @Valid SeatLayout layout) {
    }

    /**
     * Editable event details. {@code version} must be the one the admin's form was loaded with; if the event
     * changed since, the update is rejected with 409 so nobody's edit is silently overwritten.
     */
    public record UpdateEventRequest(@NotNull Long venueId,
                                     @NotBlank @Size(max = 200) String title,
                                     @NotNull EventType type,
                                     @NotNull @Future Instant startsAt,
                                     @NotNull Long version) {
    }

    /**
     * Example: one section "A" with 6 rows of 14 seats; rows 1-2 RECLINER at ₹750, rows 3-6 GOLD at ₹450.
     * <pre>
     * { "sections": [ { "name": "A", "rows": 6, "seatsPerRow": 14, "tiers": [
     *     { "name": "RECLINER", "pricePaise": 75000, "fromRow": 1, "toRow": 2 },
     *     { "name": "GOLD",     "pricePaise": 45000, "fromRow": 3, "toRow": 6 } ] } ] }
     * </pre>
     * Rows are numbered from 1 and labelled A, B, C... (so at most 26 rows per section).
     */
    public record SeatLayout(@NotEmpty @Size(max = 10) List<@Valid @NotNull Section> sections) {
    }

    public record Section(@NotBlank @Pattern(regexp = "[A-Z0-9]{1,10}",
                                  message = "must be 1-10 uppercase letters or digits") String name,
                          @Min(1) @Max(26) int rows,
                          @Min(1) @Max(50) int seatsPerRow,
                          @NotEmpty @Size(max = 5) List<@Valid @NotNull PriceTier> tiers) {
    }

    public record PriceTier(@NotBlank @Pattern(regexp = "[A-Z][A-Z_]{0,19}",
                                    message = "must be 1-20 uppercase letters or underscores") String name,
                            @Min(0) @Max(10_000_000) long pricePaise,
                            @Min(1) @Max(26) int fromRow,
                            @Min(1) @Max(26) int toRow) {
    }
}
