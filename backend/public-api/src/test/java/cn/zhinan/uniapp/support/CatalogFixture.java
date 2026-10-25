package cn.zhinan.uniapp.support;

import cn.zhinan.uniapp.authorization.port.*;
import cn.zhinan.uniapp.publicquery.domain.CatalogSnapshot;
import cn.zhinan.uniapp.publicquery.domain.CatalogSnapshot.*;
import java.time.*;
import java.util.*;

public final class CatalogFixture {
  public static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
  public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  public static java.util.Map<String, AuthorizationReadResult> permittedBatch(
      java.util.List<PublicUseContext> contexts) {
    return decisions(contexts, CatalogFixture::permitted);
  }

  public static java.util.Map<String, AuthorizationReadResult> decisions(
      java.util.List<PublicUseContext> contexts,
      java.util.function.Function<PublicUseContext, AuthorizationReadResult> factory) {
    return contexts.stream()
        .collect(java.util.stream.Collectors.toUnmodifiableMap(PublicUseContext::workId, factory));
  }

  public static AuthorizationReadResult permitted(PublicUseContext request) {
    return new AuthorizationReadResult.Decision(
        AuthorizationReadResult.Outcome.VALID,
        request,
        NOW.minusSeconds(60),
        NOW.plusSeconds(3600),
        "r1",
        "digest");
  }

  public static CatalogSnapshot snapshot() {
    var variant = new Variant("0".repeat(64) + ".png", "0".repeat(64), 10, 960, 1200, "image/png");
    var assets =
        Map.of(
            "m1",
            new Asset("m1", "a", Map.of("thumbnail", variant, "preview", variant)),
            "m2",
            new Asset("m2", "b", Map.of("thumbnail", variant, "preview", variant)));
    var brand =
        new Brand(
            "Test studio",
            "TEST STUDIO",
            "FIND TEST",
            "Test share",
            "tag",
            "description",
            List.of());
    var contact = new Contact("", "address", "hours", List.of());
    var businesses =
        List.of(
            new Business("b1", "Business One", "summary", "m1"),
            new Business("b2", "Business Two", "summary", "m2"));
    var packages =
        List.of(
            new CatalogSnapshot.Package(
                "p1",
                "b1",
                "Package",
                "summary",
                List.of("item"),
                "audience",
                30,
                "inquiry",
                "INQUIRY",
                List.of("tip"),
                List.of("notice"),
                true,
                NOW,
                "m1"));
    var works =
        List.of(
            new Work(
                "w1",
                "First",
                "summary",
                "description",
                List.of("b1"),
                "package",
                "published",
                NOW.minusSeconds(10),
                "m1",
                List.of("m1"),
                "candidate1",
                "binding1",
                "scope1"),
            new Work(
                "w2",
                "Second",
                "summary",
                "description",
                List.of("b2"),
                "package",
                "published",
                NOW,
                "m2",
                List.of("m2"),
                "candidate2",
                "binding2",
                "scope2"));
    return new CatalogSnapshot(
        "r1",
        "version1",
        "demo",
        "DEMO_ONLY",
        brand,
        contact,
        businesses,
        packages,
        works,
        assets,
        List.of(
            new Binding("WORK", "w1", "m1", 0),
            new Binding("WORK", "w2", "m2", 0),
            new Binding("BUSINESS", "b1", "m1", 0),
            new Binding("BUSINESS", "b2", "m2", 0),
            new Binding("PACKAGE", "p1", "m1", 0)),
        List.of("b2", "b1"),
        List.of());
  }
}
