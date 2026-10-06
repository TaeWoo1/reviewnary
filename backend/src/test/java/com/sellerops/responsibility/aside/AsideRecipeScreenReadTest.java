package com.sellerops.responsibility.aside;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.connector.DataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>What a seller's press is allowed to ask for.</b>
 *
 * <p>The operator lane names a CHANNEL and a DATA TYPE; the server picks the recipe. These tests pin the two
 * properties that makes that safe — the lookup only ever reaches published marketplace reads, and every
 * published recipe is read-only — because both are premises the dispatch guard relies on rather than re-checks.
 */
class AsideRecipeScreenReadTest {

    @Test
    @DisplayName("every published recipe is read-only — the premise the operator lane is built on")
    void nothingPublishedActsOnAPage() {
        for (AsideRecipe recipe : AsideRecipe.values()) {
            assertThat(recipe.mode()).as("%s", recipe).isEqualTo(AsideRecipeMode.READ_ONLY);
        }
        // If this ever fails it is not a test to fix. A recipe that acts on a marketplace needs its own
        // decision about who may ask for it — see docs/sellerops_live_approval_contract.md on WRITE.
    }

    @Test
    @DisplayName("the three screen reads are reachable by channel and data type")
    void thePublishedScreenReadsAreFound() {
        assertThat(AsideRecipe.forScreenRead("NAVER", DataType.REVIEW))
                .contains(AsideRecipe.NAVER_REVIEW_OBSERVE_V1);
        assertThat(AsideRecipe.forScreenRead("NAVER", DataType.INQUIRY))
                .contains(AsideRecipe.NAVER_PRODUCT_INQUIRY_OBSERVE_V1);
        assertThat(AsideRecipe.forScreenRead("COUPANG", DataType.REVIEW))
                .contains(AsideRecipe.COUPANG_REVIEW_OBSERVE_V1);
    }

    @Test
    @DisplayName("what is not published is not found — including the loopback recipe, which has no channel")
    void unpublishedCombinationsAreEmpty() {
        // Cafe24 reviews and inquiries arrive by official API; there is no screen read to offer.
        assertThat(AsideRecipe.forScreenRead("CAFE24", DataType.REVIEW)).isEmpty();
        assertThat(AsideRecipe.forScreenRead("CAFE24", DataType.INQUIRY)).isEmpty();
        // Coupang 문의 is read by the official API, not from the WING screen.
        assertThat(AsideRecipe.forScreenRead("COUPANG", DataType.INQUIRY)).isEmpty();
        assertThat(AsideRecipe.forScreenRead("NAVER", DataType.ORDER_SUMMARY)).isEmpty();
        assertThat(AsideRecipe.forScreenRead(null, DataType.REVIEW)).isEmpty();
        assertThat(AsideRecipe.forScreenRead("NAVER", null)).isEmpty();
        // The loopback fixture reads a surface we serve ourselves and names no channel, so no channel code can
        // reach it — a seller's press cannot ask for internal work by guessing.
        assertThat(AsideRecipe.values()).anyMatch(r -> !r.readsMarketplace());
        for (AsideRecipe recipe : AsideRecipe.values()) {
            if (!recipe.readsMarketplace()) {
                assertThat(AsideRecipe.forScreenRead("", DataType.REVIEW)).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("a dispatched read is bounded to one page, and asking for two is refused rather than clamped")
    void theBoundIsOnePage() {
        assertThat(AsideJobLimits.ONE_PAGE.maxPages()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AsideJobLimits(2))
                .isInstanceOf(com.sellerops.common.ApiException.class);
    }
}
