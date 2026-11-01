package cn.zhinan.admincontent.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhinan.admincontent.domain.CatalogDraft;
import cn.zhinan.admincontent.port.CatalogPorts.CatalogDraftRepository;
import cn.zhinan.admincontent.port.CatalogPorts.CatalogMediaReference;
import cn.zhinan.admincontent.port.CatalogPorts.CatalogPublicationRepository;
import cn.zhinan.admincontent.port.CatalogPorts.PublishCommand;
import cn.zhinan.admincontent.port.CatalogPorts.PublishResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CatalogApplicationHeroTest {
  @Test
  void previewAndPublishQualifyEnabledHeroWorksWithFeaturedWorks() {
    var seen = new java.util.concurrent.atomic.AtomicReference<List<String>>();
    CatalogPublicationRepository publications =
        new CatalogPublicationRepository() {
          @Override
          public void validateMedia(List<CatalogMediaReference> references) {}

          @Override
          public void validateWorkReferences(List<String> workIds, Instant now) {
            seen.set(List.copyOf(workIds));
          }

          @Override
          public Optional<PublishResult> findReplay(String actorId, String key, String digest) {
            return Optional.empty();
          }

          @Override
          public PublishResult publish(PublishCommand command) {
            seen.set(command.featuredWorkIds());
            return new PublishResult("c1", "r1", "catalog-r1", 1, Instant.EPOCH, false);
          }

          @Override
          public Optional<PublishResult> findCommand(String actorId, String key) {
            return Optional.empty();
          }

          @Override
          public Optional<PublishResult> commandById(String commandId) {
            return Optional.empty();
          }

          @Override
          public void withdraw(
              int expectedRevision, String actorId, String key, String digest, Instant now) {}
        };
    var app =
        new CatalogApplication(
            unusedDrafts(),
            publications,
            Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC));
    var draft = publishableDraft();
    assertThat(app.preview(draft).publishable()).isTrue();
    assertThat(seen.get()).containsExactly("featured-work", "slide-work");
    app.publish("actor", 1, "0123456789abcdef", draft, "{\"ok\":true}");
    assertThat(seen.get()).containsExactly("featured-work", "slide-work");
  }

  private static CatalogDraft publishableDraft() {
    var empty = CatalogDraft.empty();
    var businesses =
        empty.businesses().stream()
            .map(
                b ->
                    new CatalogDraft.Business(
                        b.id(), b.name(), "Synthetic", null, b.position(), true))
            .toList();
    return new CatalogDraft(
        new CatalogDraft.Brand("枝南", "Zhinan", "发现", "分享", "", "", null),
        new CatalogDraft.Contact("010", "", ""),
        List.of(),
        List.of(),
        businesses,
        List.of(),
        List.of(),
        List.of(),
        List.of("featured-work"),
        java.util.Map.of("m1", "描述"),
        List.of(
            new CatalogDraft.HeroSlide(
                "slide-1",
                "m1",
                "标题",
                "",
                "查看详情",
                new CatalogDraft.HeroTarget("WORK", "slide-work"),
                true,
                0),
            new CatalogDraft.HeroSlide(
                "slide-2",
                "m1",
                "隐藏",
                "",
                "查看详情",
                new CatalogDraft.HeroTarget("WORK", "hidden-work"),
                false,
                1)));
  }

  private static CatalogDraftRepository unusedDrafts() {
    return new CatalogDraftRepository() {
      @Override
      public Optional<cn.zhinan.admincontent.port.CatalogPorts.DraftRevision> current() {
        return Optional.empty();
      }

      @Override
      public List<cn.zhinan.admincontent.port.CatalogPorts.DraftVersion> history() {
        return List.of();
      }

      @Override
      public Optional<cn.zhinan.admincontent.port.CatalogPorts.DraftRevision> version(
          int revision) {
        return Optional.empty();
      }

      @Override
      public int save(int expectedRevision, String payloadJson, String actorId, Instant now) {
        return 0;
      }

      @Override
      public java.util.Set<String> currentWorkBusinessIds() {
        return java.util.Set.of();
      }
    };
  }
}
