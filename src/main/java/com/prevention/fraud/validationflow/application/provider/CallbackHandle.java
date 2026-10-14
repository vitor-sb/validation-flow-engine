package com.prevention.fraud.validationflow.application.provider;

import java.util.UUID;

/** What a provider needs to call back: the call id and the clear token (kept only as a hash by the engine). */
public record CallbackHandle(UUID callId, String token) {
}
