package cn.zhinan.adminruntime;

import cn.zhinan.admincontent.adapter.filesystem.FileMediaObjectWriter;
import cn.zhinan.admincontent.adapter.image.ImageIoProcessor;
import cn.zhinan.admincontent.adapter.jdbc.JdbcCatalogDraftRepository;
import cn.zhinan.admincontent.adapter.jdbc.JdbcCatalogPublicationRepository;
import cn.zhinan.admincontent.adapter.jdbc.JdbcMediaRepository;
import cn.zhinan.admincontent.adapter.jdbc.JdbcPublicationRepository;
import cn.zhinan.admincontent.adapter.jdbc.JdbcWorkRepository;
import cn.zhinan.admincontent.application.CatalogApplication;
import cn.zhinan.admincontent.application.ContentApplication;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Added as a primary source only when the content capability is explicitly enabled. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.SERVLET)
@Import(AdminContentController.class)
class AdminContentConfiguration {
  @Bean(destroyMethod = "close")
  VerifiedContentDataSources verifiedContentDataSources(AdminRuntimeProperties properties) {
    return VerifiedContentDataSources.open(properties);
  }

  @Bean
  CatalogApplication catalogApplication(
      VerifiedContentDataSources sources, AdminRuntimeProperties properties, Clock clock) {
    var content = properties.content();
    return new CatalogApplication(
        new JdbcCatalogDraftRepository(sources.business()),
        new JdbcCatalogPublicationRepository(
            sources.publication(), content.publicSchema(), content.mode()),
        clock);
  }

  @Bean
  ContentApplication contentApplication(
      VerifiedContentDataSources sources, AdminRuntimeProperties properties, Clock clock) {
    var content = properties.content();
    final FileMediaObjectWriter objects;
    try {
      objects = new FileMediaObjectWriter(content.mediaRoot());
    } catch (IllegalArgumentException rejected) {
      throw new AdminStartupException(AdminStartupException.Reason.CONFIGURATION);
    }
    return new ContentApplication(
        new ImageIoProcessor(),
        objects,
        new JdbcMediaRepository(sources.business()),
        new JdbcWorkRepository(sources.business()),
        new JdbcPublicationRepository(
            sources.publication(), content.publicSchema(), content.mode()),
        content.mode(),
        clock);
  }
}
