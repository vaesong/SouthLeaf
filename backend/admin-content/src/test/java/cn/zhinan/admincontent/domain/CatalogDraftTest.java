package cn.zhinan.admincontent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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
        java.util.Map.of());
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
            java.util.Map.of());
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
            java.util.Map.of("media-synthetic", "Synthetic logo"));
    assertThat(withLogo.hasMediaReferences()).isTrue();
    assertThat(withLogo.validated().mediaReferences())
        .singleElement()
        .satisfies(
            ref -> {
              assertThat(ref.mediaId()).isEqualTo("media-synthetic");
              assertThat(ref.altText()).isEqualTo("Synthetic logo");
              assertThat(ref.ownerType()).isEqualTo("BRAND");
            });
    var missingAlt =
        new CatalogDraft(
            withLogo.brand(),
            withLogo.contact(),
            withLogo.team(),
            withLogo.channels(),
            withLogo.businesses(),
            withLogo.packages(),
            withLogo.featuredBusinessIds(),
            withLogo.featuredPackageIds(),
            withLogo.featuredWorkIds(),
            java.util.Map.of());
    assertThatThrownBy(missingAlt::validated)
        .isInstanceOf(ContentException.class)
        .hasMessageContaining("MEDIA_ALT_SET_MISMATCH");
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
            java.util.Map.of("media-hidden", "Hidden synthetic cover"));
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
                        java.util.Map.of())
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
                        java.util.Map.of())
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
                        java.util.Map.of())
                    .validated())
        .isInstanceOf(ContentException.class)
        .hasMessageContaining("HOME_BUSINESS_UNAVAILABLE");
  }
}
