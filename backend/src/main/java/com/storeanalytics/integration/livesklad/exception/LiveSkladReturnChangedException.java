package com.storeanalytics.integration.livesklad.exception;

public class LiveSkladReturnChangedException extends LiveSkladException {

    public LiveSkladReturnChangedException() {
        super("LiveSklad return changed while cash transaction and detail were being synchronized");
    }
}
