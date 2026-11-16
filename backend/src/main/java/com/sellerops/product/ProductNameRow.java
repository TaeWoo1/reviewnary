package com.sellerops.product;

/**
 * A product's name and sku, read without the entity — what a same-name comparison needs and nothing else (2026-11-16
 * review B176). The sku is there so {@link OperatorProductName#displayNameOrNull(String, String)} can tell a sku stored
 * as the name apart, the same rule the entity path applies.
 */
public record ProductNameRow(String name, String sku) {
}
