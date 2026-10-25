package cn.zhinan.uniapp.publicquery.application;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class VersionedCursorTest {
  private final List<String> ids = List.of("a", "b", "c");

  @Test
  void stablePageAndCompletion() {
    var first = VersionedCursor.page(ids, 2, null, "v1", "works:newest", s -> s);
    assertThat(first.items()).containsExactly("a", "b");
    assertThat(
            VersionedCursor.page(ids, 2, first.nextCursor(), "v1", "works:newest", s -> s).items())
        .containsExactly("c");
  }

  @Test
  void cursorCannotCrossPublication() {
    var c = VersionedCursor.page(ids, 1, null, "v1", "q", s -> s).nextCursor();
    assertThatThrownBy(() -> VersionedCursor.page(ids, 1, c, "v2", "q", s -> s))
        .hasMessageContaining("stale");
  }

  @Test
  void cursorCannotCrossFilterOrSort() {
    var c = VersionedCursor.page(ids, 1, null, "v1", "q", s -> s).nextCursor();
    assertThatThrownBy(() -> VersionedCursor.page(ids, 1, c, "v1", "other", s -> s))
        .hasMessageContaining("stale");
  }

  @Test
  void removedAnchorIsExplicitlyInvalid() {
    var c = VersionedCursor.page(ids, 1, null, "v1", "q", s -> s).nextCursor();
    assertThatThrownBy(() -> VersionedCursor.page(List.of("b", "c"), 1, c, "v1", "q", s -> s))
        .hasMessageContaining("stale");
  }

  @Test
  void malformedAndOversizedCursorsRejected() {
    for (String c : List.of("!", "a", "A".repeat(1025)))
      assertThatThrownBy(() -> VersionedCursor.page(ids, 1, c, "v1", "q", s -> s))
          .isInstanceOf(RuntimeException.class);
  }

  @Test
  void emptyPublicationHasNoCursor() {
    var page = VersionedCursor.page(List.<String>of(), 20, null, "v1", "q", s -> s);
    assertThat(page.items()).isEmpty();
    assertThat(page.nextCursor()).isNull();
  }
}
