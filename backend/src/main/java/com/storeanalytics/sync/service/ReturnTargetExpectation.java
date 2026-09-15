package com.storeanalytics.sync.service;

import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnDetailPayload;

interface ReturnTargetExpectation {

    String externalId();

    void verify(LiveSkladReturnDetailPayload detail);
}
