package cn.zhinan.adminruntime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhinan.admincontent.domain.CatalogDraft;
import cn.zhinan.admincontent.domain.ContentException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CatalogDraftJsonTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void olderDraftJsonWithoutHeroSlidesReadsAsAnEmptyList() throws Exception {
    String current = JSON.writeValueAsString(CatalogDraft.empty());
    assertThat(current).contains("\"heroSlides\"");
    String legacy = current.replaceFirst(",?\"heroSlides\":\\[\\]", "");
    assertThat(legacy).doesNotContain("heroSlides");
    CatalogDraft draft = JSON.readValue(legacy, CatalogDraft.class);
    assertThat(draft.heroSlides()).isEmpty();
    assertThat(draft.validated().heroSlides()).isEmpty();
  }

  @Test
  void heroSlidesRoundTripThroughTheCatalogMapper() throws Exception {
    var empty = CatalogDraft.empty();
    var draft =
        new CatalogDraft(
            empty.brand(),
            empty.contact(),
            empty.team(),
            empty.channels(),
            empty.businesses(),
            empty.packages(),
            empty.featuredBusinessIds(),
            empty.featuredPackageIds(),
            empty.featuredWorkIds(),
            java.util.Map.of("m1", "描述"),
            java.util.List.of(
                new CatalogDraft.HeroSlide(
                    "slide-1",
                    "m1",
                    "标题",
                    null,
                    "查看详情",
                    new CatalogDraft.HeroTarget("ABOUT", "about"),
                    true,
                    0)));
    CatalogDraft again = JSON.readValue(JSON.writeValueAsString(draft), CatalogDraft.class);
    assertThat(again.heroSlides()).containsExactlyElementsOf(draft.heroSlides());
    assertThat(again.heroSlides().getFirst().subtitle()).isEmpty();
    assertThat(again.presentation()).isEmpty();
  }

  @Test
  void coverMediaIdRoundTripsThroughTheCatalogMapper() throws Exception {
    var empty = CatalogDraft.empty();
    var draft =
        new CatalogDraft(
            new CatalogDraft.Brand("枝南", "Zhinan", "发现", "分享", "", "第一句", null, "media-cover"),
            empty.contact(),
            empty.team(),
            empty.channels(),
            empty.businesses(),
            empty.packages(),
            empty.featuredBusinessIds(),
            empty.featuredPackageIds(),
            empty.featuredWorkIds(),
            java.util.Map.of(),
            java.util.List.of());
    CatalogDraft again = JSON.readValue(JSON.writeValueAsString(draft), CatalogDraft.class);
    assertThat(again.brand().coverMediaId()).isEqualTo("media-cover");
    assertThat(again.brand().logoMediaId()).isNull();
  }

  @Test
  void olderDraftJsonWithoutCoverMediaIdReadsAsNull() throws Exception {
    String current = JSON.writeValueAsString(CatalogDraft.empty());
    assertThat(current).contains("\"coverMediaId\":null");
    String legacy = current.replace(",\"coverMediaId\":null", "");
    assertThat(legacy).doesNotContain("coverMediaId");
    CatalogDraft draft = JSON.readValue(legacy, CatalogDraft.class);
    assertThat(draft.brand().coverMediaId()).isNull();
    assertThat(draft.validated().brand().coverMediaId()).isNull();
  }

  @Test
  void olderDraftJsonWithoutPresentationReadsAsEmpty() throws Exception {
    String current = JSON.writeValueAsString(CatalogDraft.empty());
    assertThat(current).contains("\"presentation\"");
    String legacy = current.replaceFirst(",?\"presentation\":\\{\\}", "");
    assertThat(legacy).doesNotContain("presentation");
    CatalogDraft draft = JSON.readValue(legacy, CatalogDraft.class);
    assertThat(draft.presentation()).isEmpty();
    assertThat(draft.validated().presentation()).isEmpty();
  }

  @Test
  void presentationKeepsKnownLinesAndDropsBlanks() throws Exception {
    String current = JSON.writeValueAsString(CatalogDraft.empty());
    String filled =
        current.replace(
            "\"presentation\":{}",
            "\"presentation\":{\"home.themesTitle\":\"主题\",\"home.signOff\":\"\"}");
    CatalogDraft draft = JSON.readValue(filled, CatalogDraft.class);
    assertThat(draft.presentation()).containsExactly(java.util.Map.entry("home.themesTitle", "主题"));
    String published = JSON.writeValueAsString(draft.publicationSnapshot());
    assertThat(published).contains("\"home.themesTitle\":\"主题\"");
    assertThat(published).doesNotContain("home.signOff");
  }

  @Test
  void unknownPresentationKeyIsRejected() {
    String current = JSON.writeValueAsString(CatalogDraft.empty());
    String bad = current.replace("\"presentation\":{}", "\"presentation\":{\"nope\":\"x\"}");
    assertThatThrownBy(() -> JSON.readValue(bad, CatalogDraft.class))
        .hasRootCauseInstanceOf(ContentException.class);
  }
}
