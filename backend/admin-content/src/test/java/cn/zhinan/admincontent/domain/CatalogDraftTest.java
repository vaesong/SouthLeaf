package cn.zhinan.admincontent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogDraftTest {
  private static CatalogDraft base() {
    var empty = CatalogDraft.empty();
    var businesses =
        empty.businesses().stream()
            .map(
                b ->
                    new CatalogDraft.Business(
                        b.id(), b.name(), "Synthetic summary", null, b.position(), true))
            .toList();
    return new CatalogDraft(
        new CatalogDraft.Brand(
            "Synthetic studio",
            "Synthetic",
            "Discover",
            "Synthetic studio",
            "Synthetic tagline",
            "Synthetic description",
            null),
        new CatalogDraft.Contact("", "", ""),
        List.of(),
        List.of(),
        businesses,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        java.util.Map.of(),
        List.of());
  }

  @Test
  void containsExactlySixStableBusinessIdentitiesWithoutSeededPackages() {
    var draft = CatalogDraft.empty().validated();
    assertThat(draft.businesses())
        .extracting(CatalogDraft.Business::id)
        .containsExactly("child", "family", "wedding", "portrait", "maternity", "id-photo");
    assertThat(draft.packages()).isEmpty();
    assertThat(draft.featuredBusinessIds()).isEmpty();
  }

  @Test
  void acceptsAddedAndRemovedBusinesses() {
    var kept =
        CatalogDraft.empty().businesses().stream()
            .filter(business -> !business.id().equals("id-photo"))
            .toList();
    var added = new java.util.ArrayList<>(kept);
    added.add(new CatalogDraft.Business("biz-studio", "棚拍", "", null, 20, false));
    var draft =
        new CatalogDraft(
            CatalogDraft.empty().brand(),
            CatalogDraft.empty().contact(),
            List.of(),
            List.of(),
            added,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            List.of());
    assertThat(draft.validated().businesses())
        .extracting(CatalogDraft.Business::id)
        .contains("biz-studio");
    assertThat(draft.validated().businesses())
        .extracting(CatalogDraft.Business::id)
        .doesNotContain("id-photo");
  }

  @Test
  void emptyCatalogIsNotPublishableUntilRequiredBrandAndContactAreExplicit() {
    assertThatThrownBy(() -> CatalogDraft.empty().publishable())
        .isInstanceOf(ContentException.class)
        .hasMessageContaining("BRAND_REQUIRED");
  }

  @Test
  void priceTypesRemainDistinctAndCannotSilentlySubstituteAValue() {
    var packageItem =
        new CatalogDraft.Package(
            "p1",
            "child",
            "Synthetic",
            "Synthetic",
            "Synthetic",
            null,
            "",
            "INQUIRY",
            List.of("Synthetic service"),
            List.of(),
            List.of(),
            true,
            null,
            0);
    var draft = base();
    var withPackage =
        new CatalogDraft(
            draft.brand(),
            new CatalogDraft.Contact("+81", "", ""),
            draft.team(),
            draft.channels(),
            draft.businesses(),
            List.of(packageItem),
            List.of(),
            List.of(),
            List.of(),
            java.util.Map.of(),
            List.of());
    assertThat(withPackage.validated().packages()).hasSize(1);
    assertThatThrownBy(
            () ->
                new CatalogDraft.Package(
                        "p2",
                        "child",
                        "Synthetic",
                        "Synthetic",
                        "Synthetic",
                        null,
                        "",
                        "FIXED",
                        List.of(),
                        List.of(),
                        List.of(),
                        true,
                        null,
                        0)
                    .validate())
        .isInstanceOf(ContentException.class)
        .hasMessageContaining("PRICE_REQUIRED");
    assertThatThrownBy(
            () ->
                new CatalogDraft.Package(
                        "p3",
                        "child",
                        "Synthetic",
                        "Synthetic",
                        "Synthetic",
                        null,
                        "¥1",
                        "INQUIRY",
                        List.of(),
                        List.of(),
                        List.of(),
                        true,
                        null,
                        0)
                    .validate())
        .isInstanceOf(ContentException.class)
        .hasMessageContaining("PRICE_MUST_NOT_CONTAIN_AMOUNT");
  }

  @Test
  void mediaReferencesAreExplicitlyDetectedForFailClosedPublicationQualification() {
    var draft = base();
    assertThat(draft.hasMediaReferences()).isFalse();
    var withLogo =
        new CatalogDraft(
            new CatalogDraft.Brand(
                draft.brand().name(),
                draft.brand().englishName(),
                draft.brand().discoveryHeading(),
                draft.brand().shareTitle(),
                draft.brand().tagline(),
                draft.brand().description(),
                "media-synthetic"),
            draft.contact(),
            draft.team(),
            draft.channels(),
            draft.businesses(),
            draft.packages(),
            draft.featuredBusinessIds(),
            draft.featuredPackageIds(),
            draft.featuredWorkIds(),
            java.util.Map.of("media-synthetic", "Synthetic logo"),
            List.of());
    assertThat(withLogo.hasMediaReferences()).isTrue();
    assertThat(withLogo.validated().mediaReferences())
        .singleElement()
        .satisfies(
            ref -> {
              assertThat(ref.mediaId()).isEqualTo("media-synthetic");
              assertThat(ref.altText()).isEmpty();
              assertThat(ref.ownerType()).isEqualTo("BRAND");
            });
    assertThat(withLogo.validated().mediaAltTexts())
        .containsExactly(java.util.Map.entry("media-synthetic", ""));
  }

  @Test
  void blankMediaAltValidatesAndClearsEveryReferencedDescription() {
    var draft = base();
    var brand =
        new CatalogDraft.Brand(
            draft.brand().name(),
            draft.brand().englishName(),
            draft.brand().discoveryHeading(),
            draft.brand().shareTitle(),
            draft.brand().tagline(),
            draft.brand().description(),
            "media-synthetic");
    var missing =
        new CatalogDraft(
            brand,
            draft.contact(),
            draft.team(),
            draft.channels(),
            draft.businesses(),
            draft.packages(),
            draft.featuredBusinessIds(),
            draft.featuredPackageIds(),
            draft.featuredWorkIds(),
            java.util.Map.of(),
            List.of());
    assertThat(missing.validated().mediaAltTexts())
        .containsExactly(java.util.Map.entry("media-synthetic", ""));
    var legacy = new java.util.LinkedHashMap<String, String>();
    legacy.put("media-synthetic", "x".repeat(121));
    legacy.put("unused-media", "Synthetic extra");
    var stored =
        new CatalogDraft(
            brand,
            draft.contact(),
            draft.team(),
            draft.channels(),
            draft.businesses(),
            draft.packages(),
            draft.featuredBusinessIds(),
            draft.featuredPackageIds(),
            draft.featuredWorkIds(),
            legacy,
            List.of());
    assertThat(stored.validated().mediaReferences())
        .singleElement()
        .extracting(CatalogDraft.MediaReference::altText)
        .isEqualTo("");
    assertThat(stored.validated().mediaAltTexts())
        .containsExactly(java.util.Map.entry("media-synthetic", ""));
    assertThatThrownBy(
            () ->
                new CatalogDraft(
                    brand,
                    draft.contact(),
                    draft.team(),
                    draft.channels(),
                    draft.businesses(),
                    draft.packages(),
                    draft.featuredBusinessIds(),
                    draft.featuredPackageIds(),
                    draft.featuredWorkIds(),
                    null,
                    List.of()))
        .isInstanceOf(ContentException.class)
        .hasMessageContaining("MEDIA_ALT_TEXTS_REQUIRED");
  }

  @Test
  void publicationSnapshotExcludesUnpublishedBusinessesAndTheirMediaReferences() {
    var empty = CatalogDraft.empty();
    var businesses =
        empty.businesses().stream()
            .map(
                b ->
                    new CatalogDraft.Business(
                        b.id(),
                        "Synthetic " + b.id(),
                        "Synthetic",
                        "media-hidden",
                        b.position(),
                        false))
            .toList();
    var draft =
        new CatalogDraft(
            empty.brand(),
            empty.contact(),
            empty.team(),
            empty.channels(),
            businesses,
            empty.packages(),
            List.of(),
            List.of(),
            List.of(),
            java.util.Map.of("media-hidden", "Hidden synthetic cover"),
            List.of());
    assertThat(draft.mediaReferences()).hasSize(6);
    assertThat(draft.publishedMediaReferences()).isEmpty();
    assertThat(draft.publicationSnapshot().businesses()).isEmpty();
  }

  @Test
  void homepageWorkReferencesAreUniqueAndHaveStableIds() {
    var draft = CatalogDraft.empty();
    assertThatThrownBy(
            () ->
                new CatalogDraft(
                        draft.brand(),
                        draft.contact(),
                        draft.team(),
                        draft.channels(),
                        draft.businesses(),
                        draft.packages(),
                        List.of(),
                        List.of(),
                        List.of("work-1", "work-1"),
                        java.util.Map.of(),
                        List.of())
                    .validated())
        .isInstanceOf(ContentException.class)
        .hasMessageContaining("FEATURED_WORK_DUPLICATE");
    assertThatThrownBy(
            () ->
                new CatalogDraft(
                        draft.brand(),
                        draft.contact(),
                        draft.team(),
                        draft.channels(),
                        draft.businesses(),
                        draft.packages(),
                        List.of(),
                        List.of(),
                        List.of("bad work id"),
                        java.util.Map.of(),
                        List.of())
                    .validated())
        .isInstanceOf(ContentException.class)
        .hasMessageContaining("ID_INVALID");
  }

  @Test
  void homepageCannotReferenceHiddenOrUnknownContent() {
    var draft = CatalogDraft.empty();
    assertThatThrownBy(
            () ->
                new CatalogDraft(
                        draft.brand(),
                        draft.contact(),
                        draft.team(),
                        draft.channels(),
                        draft.businesses(),
                        draft.packages(),
                        List.of("child"),
                        List.of(),
                        List.of(),
                        java.util.Map.of(),
                        List.of())
                    .validated())
        .isInstanceOf(ContentException.class)
        .hasMessageContaining("HOME_BUSINESS_UNAVAILABLE");
  }

  @Test
  void missingHeroSlidesStayEmptyAndDoNotRejectAnOlderDraft() {
    var draft = base();
    var legacy =
        new CatalogDraft(
            draft.brand(),
            draft.contact(),
            draft.team(),
            draft.channels(),
            draft.businesses(),
            draft.packages(),
            draft.featuredBusinessIds(),
            draft.featuredPackageIds(),
            draft.featuredWorkIds(),
            draft.mediaAltTexts(),
            null);
    assertThat(legacy.heroSlides()).isEmpty();
    assertThat(legacy.validated().publicationSnapshot().heroSlides()).isEmpty();
  }

  @Test
  void heroSlidesRejectASixthItemAndDuplicatePosition() {
    var slides = new java.util.ArrayList<CatalogDraft.HeroSlide>();
    var alts = new java.util.LinkedHashMap<String, String>();
    for (int i = 0; i < 6; i++) {
      slides.add(slide("s" + i, "m" + i, i, false, new CatalogDraft.HeroTarget("ABOUT", "about")));
      alts.put("m" + i, "Synthetic description");
    }
    assertThatThrownBy(() -> withHeroes(slides, alts).validated())
        .isInstanceOf(ContentException.class)
        .hasMessageContaining("HERO_LIMIT");
    var five = slides.subList(0, 5);
    assertThatThrownBy(
            () ->
                withHeroes(
                        List.of(five.get(0), slide("other", "m0", 0, false, five.get(0).target())),
                        Map.of("m0", "Synthetic description"))
                    .validated())
        .isInstanceOf(ContentException.class)
        .hasMessageContaining("HERO_POSITION_DUPLICATE");
    assertThat(withHeroes(five, Map.of()).validated().mediaAltTexts().values()).containsOnly("");
    assertThatThrownBy(
            () ->
                withHeroes(
                        List.of(
                            slide(
                                "s0",
                                "m0",
                                0,
                                true,
                                new CatalogDraft.HeroTarget("ABOUT", "studio"))),
                        Map.of("m0", "Synthetic description"))
                    .validated())
        .isInstanceOf(ContentException.class)
        .hasMessageContaining("HERO_TARGET_INVALID");
  }

  @Test
  void publishableChecksOnlyEnabledSlidesAndSnapshotOmitsHiddenOnes() {
    var hidden =
        slide("hidden", "m-hidden", 2, false, new CatalogDraft.HeroTarget("PACKAGE", "missing"));
    var shown =
        slide("shown", "m-shown", 0, true, new CatalogDraft.HeroTarget("BUSINESS", "child"));
    var draft =
        withHeroes(
            List.of(hidden, shown),
            Map.of("m-hidden", "Hidden description", "m-shown", "Shown description"));
    assertThat(draft.publishable().publicationSnapshot().heroSlides())
        .extracting(CatalogDraft.HeroSlide::id)
        .containsExactly("shown");
    assertThat(draft.publishedMediaReferences())
        .extracting(CatalogDraft.MediaReference::ownerType)
        .containsExactly("HERO");
    assertThat(draft.publicationWorkIds()).isEmpty();
    var work = slide("work", "m-work", 1, true, new CatalogDraft.HeroTarget("WORK", "work-1"));
    var withWork =
        withHeroes(
            List.of(shown, work),
            Map.of("m-shown", "Shown description", "m-work", "Work description"));
    assertThat(withWork.publicationWorkIds()).containsExactly("work-1");
    assertThatThrownBy(
            () ->
                withHeroes(
                        List.of(
                            slide(
                                "pkg",
                                "m-pkg",
                                0,
                                true,
                                new CatalogDraft.HeroTarget("PACKAGE", "missing"))),
                        Map.of("m-pkg", "Package description"))
                    .publishable())
        .isInstanceOf(ContentException.class)
        .hasMessageContaining("HERO_PACKAGE_UNAVAILABLE");
    assertThatThrownBy(
            () ->
                withHeroes(
                        List.of(
                            slide(
                                "biz",
                                "m-biz",
                                0,
                                true,
                                new CatalogDraft.HeroTarget("BUSINESS", "missing"))),
                        Map.of("m-biz", "Business description"))
                    .publishable())
        .isInstanceOf(ContentException.class)
        .hasMessageContaining("HERO_BUSINESS_UNAVAILABLE");
  }

  private static CatalogDraft withHeroes(
      List<CatalogDraft.HeroSlide> slides, Map<String, String> alts) {
    var draft = base();
    return new CatalogDraft(
        draft.brand(),
        new CatalogDraft.Contact("010", "", ""),
        draft.team(),
        draft.channels(),
        draft.businesses(),
        draft.packages(),
        draft.featuredBusinessIds(),
        draft.featuredPackageIds(),
        draft.featuredWorkIds(),
        alts,
        slides);
  }

  private static CatalogDraft.HeroSlide slide(
      String id, String mediaId, int position, boolean enabled, CatalogDraft.HeroTarget target) {
    return new CatalogDraft.HeroSlide(id, mediaId, "标题", "", "查看详情", target, enabled, position);
  }
}
