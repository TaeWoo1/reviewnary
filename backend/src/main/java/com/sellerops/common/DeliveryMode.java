package com.sellerops.common;

/**
 * Where an execution was sent, recorded once when the execution row is created (V187, 2026-11-09, demo handoff §7.12
 * item 5) — never re-derived from today's configuration.
 *
 * <p>{@link #UNKNOWN} is what every row written before V187 carries, and what a row whose route was not decided at
 * creation carries. It is not corrected later: a history that cannot say whether it was real stays 「모름」 rather than
 * being painted with whatever the deployment's mock list happens to be now.
 */
public enum DeliveryMode {
    /** Sent through a real channel adapter. */
    REAL,
    /** Sent through a development simulation — nothing reached a real channel or customer. */
    SIMULATED,
    /** Not recorded (before V187), or no route was decided when the row was created. */
    UNKNOWN
}
