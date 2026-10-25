package cn.zhinan.uniapp.media.application;

import static org.assertj.core.api.Assertions.*;

import cn.zhinan.uniapp.authorization.port.*;
import cn.zhinan.uniapp.publicquery.application.CatalogReadService;
import cn.zhinan.uniapp.support.CatalogFixture;
import cn.zhinan.uniapp.works.application.PublicVisibilityPolicy;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class PublicMediaServiceTest {
  @Test
  void freshAuthorizationIsRecheckedAfterBuffering() {
    var revoked = new AtomicBoolean();
    PublicAuthorizationEvaluator auth =
        contexts ->
            CatalogFixture.decisions(
                contexts,
                c ->
                    revoked.get()
                        ? new AuthorizationReadResult.Decision(
                            AuthorizationReadResult.Outcome.INVALID, null, null, null, null, null)
                        : CatalogFixture.permitted(c));
    // m1 also has a public business binding; use a snapshot with work-only bindings to test work
    // revocation.
    var original = CatalogFixture.snapshot();
    var snapshot =
        new cn.zhinan.uniapp.publicquery.domain.CatalogSnapshot(
            original.releaseId(),
            original.contentVersion(),
            original.dataMode(),
            original.approvalStatus(),
            original.brand(),
            original.contact(),
            original.businesses(),
            original.packages(),
            original.works(),
            original.assets(),
            original.bindings().stream().filter(b -> b.ownerType().equals("WORK")).toList(),
            original.featuredBusinesses(),
            original.featuredPackages());
    var service =
        new PublicMediaService(
            new CatalogReadService(() -> snapshot, "demo"),
            new PublicVisibilityPolicy(auth, CatalogFixture.CLOCK),
            v -> {
              revoked.set(true);
              return new byte[] {1};
            });
    assertThatThrownBy(() -> service.read("m1", "preview")).hasMessageContaining("unavailable");
  }

  @Test
  void unknownMediaDoesNotTouchStorage() {
    var service =
        new PublicMediaService(
            new CatalogReadService(CatalogFixture::snapshot, "demo"),
            new PublicVisibilityPolicy(CatalogFixture::permittedBatch, CatalogFixture.CLOCK),
            v -> {
              throw new AssertionError("store must not run");
            });
    assertThatThrownBy(() -> service.read("missing", "preview"))
        .hasMessageContaining("unavailable");
  }

  @Test
  void contentDoesNotExposeMutableBuffer() {
    byte[] original = {1};
    var c = new PublicMediaService.Content(original, "image/png");
    original[0] = 2;
    byte[] copy = c.bytes();
    copy[0] = 3;
    assertThat(c.bytes()).containsExactly((byte) 1);
  }

  @Test
  void malformedInputIsRejectedBeforeStorage() {
    var service =
        new PublicMediaService(
            new CatalogReadService(CatalogFixture::snapshot, "demo"),
            new PublicVisibilityPolicy(CatalogFixture::permittedBatch, CatalogFixture.CLOCK),
            v -> {
              throw new AssertionError("store must not run");
            });
    assertThatThrownBy(() -> service.read("../m1", "preview"))
        .isInstanceOf(cn.zhinan.uniapp.publicquery.PublicQueryException.class)
        .hasMessageContaining("Invalid");
    assertThatThrownBy(() -> service.read("m1", "original"))
        .isInstanceOf(cn.zhinan.uniapp.publicquery.PublicQueryException.class)
        .hasMessageContaining("Invalid");
  }
}
