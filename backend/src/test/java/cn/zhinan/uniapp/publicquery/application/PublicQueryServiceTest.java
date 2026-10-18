package cn.zhinan.uniapp.publicquery.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.zhinan.uniapp.authorization.port.*;
import cn.zhinan.uniapp.publicquery.port.*;
import cn.zhinan.uniapp.support.CatalogFixture;
import cn.zhinan.uniapp.works.application.PublicVisibilityPolicy;
import org.junit.jupiter.api.Test;

class PublicQueryServiceTest {
  private PublicQueryService service(PublicAuthorizationEvaluator auth) {
    return new PublicQueryService(
        new CatalogReadService(CatalogFixture::snapshot, "demo"),
        new PublicVisibilityPolicy(auth, CatalogFixture.CLOCK),
        CatalogFixture.CLOCK);
  }

  @Test
  void listEvaluatesScopedFactsOnceAsOneBatch() {
    PublicAuthorizationEvaluator auth = mock(PublicAuthorizationEvaluator.class);
    when(auth.evaluate(anyList()))
        .thenAnswer(invocation -> CatalogFixture.permittedBatch(invocation.getArgument(0)));
    assertThat(service(auth).readWorks(null, "newest", 20, null).data().items()).hasSize(2);
    verify(auth, times(1)).evaluate(argThat(contexts -> contexts.size() == 2));
  }

  @Test
  void incompleteFactBatchIsNotTreatedAsUnknownAuthorization() {
    assertThatThrownBy(
            () -> service(contexts -> java.util.Map.of()).readWorks(null, "newest", 20, null))
        .hasMessageContaining("dependency");
  }

  @Test
  void homePreservesExplicitCurationWithoutDuplicatePackages() {
    var home = service(CatalogFixture::permittedBatch).readHome();
    assertThat(home.data().businesses()).extracting(b -> b.id()).containsExactly("b2", "b1");
    assertThat(home.data().packages()).isEmpty();
    assertThat(home.data().heroItems()).hasSize(2);
  }

  @Test
  void sortAndFilterAreAppliedBeforePaging() {
    var s = service(CatalogFixture::permittedBatch);
    assertThat(s.readWorks(null, "newest", 1, null).data().items().getFirst().id()).isEqualTo("w2");
    assertThat(s.readWorks("b1", "oldest", 20, null).data().items())
        .extracting(w -> w.id())
        .containsExactly("w1");
  }

  @Test
  void requestDataAndMetadataUseOneSnapshot() {
    PublishedCatalogRepository repository = mock(PublishedCatalogRepository.class);
    when(repository.readCurrent()).thenReturn(CatalogFixture.snapshot());
    var s =
        new PublicQueryService(
            new CatalogReadService(repository, "demo"),
            new PublicVisibilityPolicy(CatalogFixture::permittedBatch, CatalogFixture.CLOCK),
            CatalogFixture.CLOCK);
    var response = s.readHome();
    assertThat(response.meta().contentVersion()).isEqualTo("version1");
    verify(repository, times(1)).readCurrent();
  }

  @Test
  void unknownAuthorizationHidesWorksButNotBusinesses() {
    var s =
        service(
            contexts ->
                CatalogFixture.decisions(
                    contexts,
                    c ->
                        new AuthorizationReadResult.Decision(
                            AuthorizationReadResult.Outcome.UNKNOWN,
                            null,
                            null,
                            null,
                            null,
                            null)));
    assertThat(s.readWorks(null, "newest", 20, null).data().items()).isEmpty();
    assertThat(s.readBusinesses().data().items()).hasSize(2);
    assertThatThrownBy(() -> s.readWork("w1")).hasMessageContaining("unavailable");
  }

  @Test
  void authorizationOutageIsNotASuccessfulEmptyList() {
    assertThatThrownBy(
            () ->
                service(
                        contexts ->
                            CatalogFixture.decisions(
                                contexts,
                                c -> new AuthorizationReadResult.DependencyUnavailable("test")))
                    .readWorks(null, "newest", 20, null))
        .hasMessageContaining("dependency");
  }

  @Test
  void productionRefusesDemoPublication() {
    assertThatThrownBy(() -> new CatalogReadService(CatalogFixture::snapshot, "production").load())
        .hasMessageContaining("mismatch");
  }

  @Test
  void databaseFailureIsNotReplacedByFixtureOrCache() {
    var s =
        new PublicQueryService(
            new CatalogReadService(
                () -> {
                  throw new CatalogStorageException("offline", null);
                },
                "demo"),
            new PublicVisibilityPolicy(CatalogFixture::permittedBatch, CatalogFixture.CLOCK),
            CatalogFixture.CLOCK);
    assertThatThrownBy(s::readHome).isInstanceOf(CatalogStorageException.class);
  }

  @Test
  void invalidIdsLimitsAndSortsAreRejected() {
    var s = service(CatalogFixture::permittedBatch);
    assertThatThrownBy(() -> s.readWork("../x")).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> s.readWorks(null, "bad", 20, null))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> s.readPackages(null, 0, null)).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> s.readPackages(null, 51, null)).isInstanceOf(RuntimeException.class);
  }

  @Test
  void wrongOwnerCannotReadMediaReference() {
    var p =
        new CatalogProjection(
            CatalogFixture.snapshot(),
            new PublicVisibilityPolicy(CatalogFixture::permittedBatch, CatalogFixture.CLOCK));
    assertThat(p.media("w1", "m2", "preview")).isEmpty();
  }

  @Test
  void immutableSnapshotCannotBeChangedByCallers() {
    assertThatThrownBy(() -> CatalogFixture.snapshot().works().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void packageAndBusinessDetailsUseTheSamePublishedModel() {
    var s = service(CatalogFixture::permittedBatch);
    assertThat(s.readBusiness("b1").data().packages()).hasSize(1);
    assertThat(s.readPackage("p1").data().serviceItems()).containsExactly("item");
  }
}
