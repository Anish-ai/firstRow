package com.firstrow.admin;

import com.firstrow.admin.AdminDtos.PriceTier;
import com.firstrow.admin.AdminDtos.Section;
import com.firstrow.admin.AdminDtos.SeatLayout;
import com.firstrow.common.ApiException;
import com.firstrow.event.SeatSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatLayoutGeneratorTest {

    private static final PriceTier RECLINER_ROWS_1_2 = new PriceTier("RECLINER", 75_000, 1, 2);
    private static final PriceTier GOLD_ROWS_3_6 = new PriceTier("GOLD", 45_000, 3, 6);

    @Test
    void generatesOneSeatPerRowAndNumberWithTheRightTier() {
        SeatLayout layout = layout(new Section("A", 6, 14, List.of(RECLINER_ROWS_1_2, GOLD_ROWS_3_6)));

        List<SeatSpec> seats = SeatLayoutGenerator.generate(layout);

        assertThat(seats).hasSize(6 * 14);
        assertThat(seats.getFirst()).isEqualTo(new SeatSpec("A", "A", 1, "RECLINER", 75_000));
        assertThat(seats.getLast()).isEqualTo(new SeatSpec("A", "F", 14, "GOLD", 45_000));
        assertThat(seats).filteredOn(s -> s.row().equals("B")).allMatch(s -> s.priceTier().equals("RECLINER"));
        assertThat(seats).filteredOn(s -> s.row().equals("C")).allMatch(s -> s.priceTier().equals("GOLD"));
    }

    @Test
    void supportsMultipleSections() {
        PriceTier stand = new PriceTier("STAND", 60_000, 1, 2);
        SeatLayout layout = layout(
                new Section("NORTH", 2, 5, List.of(stand)),
                new Section("SOUTH", 2, 5, List.of(stand)));

        assertThat(SeatLayoutGenerator.generate(layout))
                .hasSize(20)
                .extracting(SeatSpec::section)
                .containsOnly("NORTH", "SOUTH");
    }

    @Test
    void rowLabelsRunFromAToZ() {
        assertThat(SeatLayoutGenerator.rowLabel(1)).isEqualTo("A");
        assertThat(SeatLayoutGenerator.rowLabel(26)).isEqualTo("Z");
    }

    @Test
    void rejectsRowWithoutTier() {
        SeatLayout layout = layout(new Section("A", 6, 10, List.of(RECLINER_ROWS_1_2)));

        assertThatThrownBy(() -> SeatLayoutGenerator.generate(layout))
                .isInstanceOf(ApiException.class)
                .hasMessage("Section A: row C has no price tier");
    }

    @Test
    void rejectsOverlappingTiers() {
        SeatLayout layout = layout(new Section("A", 6, 10,
                List.of(RECLINER_ROWS_1_2, new PriceTier("GOLD", 45_000, 2, 6))));

        assertThatThrownBy(() -> SeatLayoutGenerator.generate(layout))
                .isInstanceOf(ApiException.class)
                .hasMessage("Section A: row B is in both tier RECLINER and tier GOLD");
    }

    @Test
    void rejectsTierBeyondLastRow() {
        SeatLayout layout = layout(new Section("A", 2, 10, List.of(new PriceTier("GOLD", 45_000, 1, 3))));

        assertThatThrownBy(() -> SeatLayoutGenerator.generate(layout))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("toRow 3 is beyond the section's 2 rows");
    }

    @Test
    void rejectsReversedTierRange() {
        SeatLayout layout = layout(new Section("A", 4, 10, List.of(new PriceTier("GOLD", 45_000, 3, 1))));

        assertThatThrownBy(() -> SeatLayoutGenerator.generate(layout))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("fromRow must not be after toRow");
    }

    @Test
    void rejectsDuplicateSectionNames() {
        Section a = new Section("A", 2, 5, List.of(new PriceTier("GOLD", 45_000, 1, 2)));

        assertThatThrownBy(() -> SeatLayoutGenerator.generate(layout(a, a)))
                .isInstanceOf(ApiException.class)
                .hasMessage("Duplicate section name A");
    }

    private static SeatLayout layout(Section... sections) {
        return new SeatLayout(List.of(sections));
    }
}
