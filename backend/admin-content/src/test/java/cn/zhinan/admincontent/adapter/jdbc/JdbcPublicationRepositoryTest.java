package cn.zhinan.admincontent.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JdbcPublicationRepositoryTest {
  @Test
  void visiblePointerAcceptsMysqlBooleanAndNumericRepresentations() {
    assertThat(JdbcPublicationRepository.isVisible(Boolean.TRUE)).isTrue();
    assertThat(JdbcPublicationRepository.isVisible(Boolean.FALSE)).isFalse();
    assertThat(JdbcPublicationRepository.isVisible(1)).isTrue();
    assertThat(JdbcPublicationRepository.isVisible(0)).isFalse();
    assertThat(JdbcPublicationRepository.isVisible("1")).isFalse();
    assertThat(JdbcPublicationRepository.isVisible(null)).isFalse();
  }
}
