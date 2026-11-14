package com.firstrow.admin;

import com.firstrow.admin.AdminDtos.PriceTier;
import com.firstrow.admin.AdminDtos.Section;
import com.firstrow.admin.AdminDtos.SeatLayout;
import com.firstrow.common.ApiException;
import com.firstrow.event.SeatSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns an admin's layout spec into the list of seats to insert.
 * Pure function (no database), so it is easy to unit test.
 * Rejects layouts where a row has no price tier, or two tiers claim the same row.
 */
public final class SeatLayoutGenerator {

    private SeatLayoutGenerator() {
    }

    public static List<SeatSpec> generate(SeatLayout layout) {
        List<SeatSpec> seats = new ArrayList<>();
        Set<String> sectionNames = new HashSet<>();

        for (Section section : layout.sections()) {
            if (!sectionNames.add(section.name())) {
                throw ApiException.badRequest("Duplicate section name " + section.name());
            }
            PriceTier[] tierByRow = assignTiersToRows(section);

            for (int row = 1; row <= section.rows(); row++) {
                PriceTier tier = tierByRow[row];
                for (int seat = 1; seat <= section.seatsPerRow(); seat++) {
                    seats.add(new SeatSpec(section.name(), rowLabel(row), seat, tier.name(), tier.pricePaise()));
                }
            }
        }
        return seats;
    }

    /** Row 1 is "A", row 2 is "B", ... row 26 is "Z". */
    static String rowLabel(int row) {
        return String.valueOf((char) ('A' + row - 1));
    }

    /** Returns tierByRow[row] for rows 1..section.rows(); every row must get exactly one tier. */
    private static PriceTier[] assignTiersToRows(Section section) {
        PriceTier[] tierByRow = new PriceTier[section.rows() + 1];

        for (PriceTier tier : section.tiers()) {
            if (tier.fromRow() > tier.toRow()) {
                throw ApiException.badRequest("Section %s, tier %s: fromRow must not be after toRow"
                        .formatted(section.name(), tier.name()));
            }
            if (tier.toRow() > section.rows()) {
                throw ApiException.badRequest("Section %s, tier %s: toRow %d is beyond the section's %d rows"
                        .formatted(section.name(), tier.name(), tier.toRow(), section.rows()));
            }
            for (int row = tier.fromRow(); row <= tier.toRow(); row++) {
                if (tierByRow[row] != null) {
                    throw ApiException.badRequest("Section %s: row %s is in both tier %s and tier %s"
                            .formatted(section.name(), rowLabel(row), tierByRow[row].name(), tier.name()));
                }
                tierByRow[row] = tier;
            }
        }

        for (int row = 1; row <= section.rows(); row++) {
            if (tierByRow[row] == null) {
                throw ApiException.badRequest("Section %s: row %s has no price tier"
                        .formatted(section.name(), rowLabel(row)));
            }
        }
        return tierByRow;
    }
}
