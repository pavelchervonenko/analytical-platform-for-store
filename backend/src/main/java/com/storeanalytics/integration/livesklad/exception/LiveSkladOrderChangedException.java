package com.storeanalytics.integration.livesklad.exception;

public class LiveSkladOrderChangedException extends LiveSkladException {

    public LiveSkladOrderChangedException() {
        super("LiveSklad order changed while list and detail were being synchronized");
    }
}
