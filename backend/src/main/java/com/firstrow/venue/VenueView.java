package com.firstrow.venue;

public record VenueView(Long id, String name, String city) {

    public static VenueView from(Venue venue) {
        return new VenueView(venue.getId(), venue.getName(), venue.getCity());
    }
}
