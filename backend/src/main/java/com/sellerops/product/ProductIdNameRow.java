package com.sellerops.product;

import java.util.UUID;

/** A product's id and name only — for a read that labels rows it already counted (product analysis, 2026-11-16). */
public record ProductIdNameRow(UUID id, String name) {
}
