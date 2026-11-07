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
            "\"presentation\":{}", "\"presentation\":{\"tabs.home\":\"首页\",\"home.signOff\":\"\"}");
    CatalogDraft draft = JSON.readValue(filled, CatalogDraft.class);
    assertThat(draft.presentation()).containsExactly(java.util.Map.entry("tabs.home", "首页"));
  }

  @Test
  void unknownPresentationKeyIsRejected() {
    String current = JSON.writeValueAsString(CatalogDraft.empty());
    String bad = current.replace("\"presentation\":{}", "\"presentation\":{\"nope\":\"x\"}");
    assertThatThrownBy(() -> JSON.readValue(bad, CatalogDraft.class))
        .hasRootCauseInstanceOf(ContentException.class);
  }
}
