package cn.zhinan.uniapp.publicquery.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhinan.uniapp.publicquery.port.CatalogStorageException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HeroSlideJsonTest {
  @Test
  void missingHeroSlidesAreEmptyAndABrokenSlideFailsTheRelease() {
    assertThat(JdbcPublishedCatalogRepository.heroSlides(null)).isEmpty();
    assertThatThrownBy(() -> JdbcPublishedCatalogRepository.heroSlides(Map.of("id", "x")))
        .isInstanceOf(CatalogStorageException.class);
    var parsed =
        JdbcPublishedCatalogRepository.heroSlides(
            List.of(
                Map.of(
                    "id",
                    "slide-1",
                    "mediaId",
                    "m1",
                    "title",
                    "标题",
                    "subtitle",
                    "",
                    "actionText",
                    "查看详情",
                    "target",
                    Map.of("type", "ABOUT", "id", "about"),
                    "enabled",
                    true,
                    "position",
                    0)));
    assertThat(parsed).hasSize(1);
    assertThat(parsed.getFirst().targetType()).isEqualTo("ABOUT");
    assertThat(parsed.getFirst().targetId()).isEqualTo("about");
    assertThat(parsed.getFirst().enabled()).isTrue();
  }
}
