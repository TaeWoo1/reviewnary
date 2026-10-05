package com.sellerops.product;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    List<Product> findAllByOrgId(UUID orgId);

    /**
     * Resolve a bounded set of products within one org — the batch primitive behind a
     * read that needs display names for a page of rows it already holds.
     *
     * <p>Org-scoped on purpose, and that is the point rather than a detail:
     * {@code reviews.product_id} is a bare FK to {@code products(id)} with no org
     * constraint in the schema, so a product id read off a row is NOT proof of same-org
     * ownership. Filtering by {@code orgId} here means a cross-org id resolves to
     * nothing instead of leaking another tenant's catalog name. Prefer this over
     * {@link #findAllById} (no org filter) and over {@link #findAllByOrgId} (loads the
     * org's whole catalog to answer for a handful of ids).
     *
     * <p>Callers must keep {@code ids} bounded — one clamped page's worth. Every id is
     * a primary-key hit, so the cost is the caller's page size, not the catalog size.
     */
    List<Product> findAllByOrgIdAndIdIn(UUID orgId, Collection<UUID> ids);

    Optional<Product> findByOrgIdAndSku(UUID orgId, String sku);

    /**
     * <b>The names of these products, including the ones the {@code realDataOnly} filter hides.</b>
     *
     * <p>Native, which is how this repository's own contract says a read that must see everything is
     * written ({@code RealDataOnly}: "reads that must see everything regardless use findById or a
     * native query, neither of which a Hibernate filter touches"). The knowledge workspace needs it
     * because a piece of knowledge the seller wrote is attached to a product, and a row that cannot
     * name the product it applies to is unreadable — the 자료 list beside it already resolves names
     * the same way, one {@code findById} per document, and one screen must not answer the same
     * question two ways.
     *
     * <p><b>It names; it does not admit to a count.</b> Whether a product is part of 「상품 294개」 is
     * still decided by the ordinary filtered read, so a manufactured product can be named on a row
     * that exists without being counted into a denominator it is not in.
     *
     * <p>The id comes back as text and not as a {@code uuid}: a native select hands the driver's own
     * representation straight through, which is a {@code byte[]} on one of the two databases this
     * runs on, and a cast in Java would then be a cast that works in production and fails in the
     * tests. Casting in SQL makes both ends the same thing.
     *
     * @param ids never empty — {@code in ()} is not valid SQL; the caller returns early instead
     */
    @org.springframework.data.jpa.repository.Query(
            value = "select cast(id as varchar) as id, name from products where org_id = :orgId and id in (:ids)",
            nativeQuery = true)
    List<Object[]> namesOfAnyOrigin(@org.springframework.data.repository.query.Param("orgId") UUID orgId,
                                    @org.springframework.data.repository.query.Param("ids") Collection<UUID> ids);

    /**
     * How many products this org holds — the 「상품 정보」 line on the knowledge screen.
     *
     * <p>It is there so a seller who has just connected a channel is not told they have nothing.
     * What reviewnary already knows without being taught is the catalogue it collected, and stating
     * that number is the difference between 「지식을 입력하세요」 and 「이미 읽은 것이 있습니다」.
     */
    long countByOrgId(UUID orgId);


    Optional<Product> findFirstByOrgIdAndName(UUID orgId, String name);

    /**
     * Insert a product only if its {@code (org_id, sku)} is not already present — a
     * transaction-safe upsert primitive using SQL-standard {@code MERGE} (supported by
     * both PostgreSQL 15 and H2). With only a {@code WHEN NOT MATCHED THEN INSERT}
     * branch, an already-present row is a no-op (0 rows) rather than a constraint
     * violation, so a concurrent creation of the same SKU never poisons the enclosing
     * transaction and never overwrites the existing product's name. Callers re-select
     * the row afterwards.
     *
     * <p>{@code data_origin} is written explicitly. A native statement bypasses the entity, so the
     * field initializer that supplies REAL everywhere else does not apply here — and REAL is exactly
     * right for this path: every caller of this method is resolving a product out of data that
     * actually arrived (an import row, an ingested inquiry). Omitting the column made every product
     * created this way fail the NOT NULL constraint, which is a better failure than the alternative
     * of a nullable column quietly admitting unclassified rows past the filter.
     */
    @Modifying
    @Query(value = "merge into products t "
            // Every parameter is CAST explicitly. A bare parameter inside a VALUES row constructor has
            // no column to take its type from — PostgreSQL resolves it to `text`, and the failure lands
            // far away as "column created_at is of type timestamp with time zone but expression is of
            // type text". It was invisible for as long as it was: the tests run on H2, which infers the
            // types happily, and this statement is only reached when a genuinely NEW sku appears. On the
            // demo org that was the Cafe24 review promotion — 133 real articles that silently promoted
            // none, three sync runs in a row, logged as a swallowed warning.
            + "using (values (cast(:id as uuid), cast(:orgId as uuid), cast(:name as varchar), "
            + "cast(:sku as varchar), cast(:now as timestamp with time zone))) s(id, org_id, name, sku, ts) "
            + "on t.org_id = s.org_id and t.sku = s.sku "
            + "when not matched then insert (id, org_id, name, sku, status, created_at, updated_at, data_origin) "
            + "values (s.id, s.org_id, s.name, s.sku, 'ACTIVE', s.ts, s.ts, 'REAL')",
            nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("orgId") UUID orgId, @Param("name") String name,
                       @Param("sku") String sku, @Param("now") Instant now);
}
