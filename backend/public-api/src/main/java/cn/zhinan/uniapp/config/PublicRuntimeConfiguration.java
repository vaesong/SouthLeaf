package cn.zhinan.uniapp.config;

import cn.zhinan.uniapp.authorization.port.PublicAuthorizationEvaluator;
import cn.zhinan.uniapp.authorization.port.PublicOverlayAuthorizationEvaluator;
import cn.zhinan.uniapp.media.application.PublicMediaService;
import cn.zhinan.uniapp.media.port.MediaObjectStore;
import cn.zhinan.uniapp.publicquery.application.CatalogReadService;
import cn.zhinan.uniapp.publicquery.application.PublicQueryService;
import cn.zhinan.uniapp.publicquery.port.PublishedCatalogRepository;
import cn.zhinan.uniapp.works.application.PublicVisibilityPolicy;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root owns framework wiring; application/domain/ports are plain Java. */
@Configuration(proxyBeanMethods = false)
public class PublicRuntimeConfiguration {
  @Bean
  public Clock publicClock() {
    return Clock.systemUTC();
  }

  @Bean
  public CatalogReadService catalogReadService(
      PublishedCatalogRepository repository, @Value("${southleaf.public.mode}") String mode) {
    return new CatalogReadService(repository, mode);
  }

  @Bean
  public PublicVisibilityPolicy publicVisibilityPolicy(
      PublicAuthorizationEvaluator authorization,
      PublicOverlayAuthorizationEvaluator overlayAuthorization,
      Clock clock) {
    return new PublicVisibilityPolicy(authorization, overlayAuthorization, clock);
  }

  @Bean
  public PublicQueryService publicQueryService(
      CatalogReadService catalogs, PublicVisibilityPolicy visibility, Clock clock) {
    return new PublicQueryService(catalogs, visibility, clock);
  }

  @Bean
  public PublicMediaService publicMediaService(
      CatalogReadService catalogs, PublicVisibilityPolicy visibility, MediaObjectStore store) {
    return new PublicMediaService(catalogs, visibility, store);
  }

  @Bean
  public ApplicationRunner publishedCatalogReady(CatalogReadService catalogs) {
    return arguments -> catalogs.load();
  }
}
