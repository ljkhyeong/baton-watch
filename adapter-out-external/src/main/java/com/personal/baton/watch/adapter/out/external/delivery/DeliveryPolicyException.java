package com.personal.baton.watch.adapter.out.external.delivery;

final class DeliveryPolicyException extends Exception {

    DeliveryPolicyException() {
        super("delivery destination rejected");
    }
}
