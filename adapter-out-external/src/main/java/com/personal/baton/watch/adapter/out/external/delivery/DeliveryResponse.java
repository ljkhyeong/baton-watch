package com.personal.baton.watch.adapter.out.external.delivery;

import java.time.Instant;

record DeliveryResponse(int statusCode, Instant retryNotBefore) {}
