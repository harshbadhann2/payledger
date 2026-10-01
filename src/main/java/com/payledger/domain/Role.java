package com.payledger.domain;

public enum Role {
    CUSTOMER,
    MERCHANT,
    /** Internal owner of the top-up funding wallet. Cannot be registered or log in. */
    SYSTEM
}
