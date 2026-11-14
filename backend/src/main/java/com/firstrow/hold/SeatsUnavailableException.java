package com.firstrow.hold;

import com.firstrow.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;

/** 409: some requested seats were already taken. Carries their ids so the UI can highlight them. */
public class SeatsUnavailableException extends ApiException {

    private final List<Long> unavailableSeatIds;

    public SeatsUnavailableException(List<Long> unavailableSeatIds) {
        super(HttpStatus.CONFLICT, "Sorry, some of these seats were just taken by someone else.");
        this.unavailableSeatIds = List.copyOf(unavailableSeatIds);
    }

    public List<Long> getUnavailableSeatIds() {
        return unavailableSeatIds;
    }
}
