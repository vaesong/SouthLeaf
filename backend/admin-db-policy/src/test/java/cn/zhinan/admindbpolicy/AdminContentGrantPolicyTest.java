package cn.zhinan.admindbpolicy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class AdminContentGrantPolicyTest {
  private static final String ADMIN = "southleaf_admin_s2b";
  private static final String PUB = "southleaf_public_s2b";

  static List<String> grants(AdminContentGrantPolicy.Identity identity, String user) {
    Map<String, List<String>> byTable = new TreeMap<>();
    for (String key : AdminContentGrantPolicy.required(identity)) {
      String[] parts = key.split(":");
      String db = parts[0].equals("admin") ? ADMIN : PUB;
      byTable
          .computeIfAbsent("`" + db + "`.`" + parts[1] + "`", k -> new ArrayList<>())
          .add(parts[2]);
    }
    var rows = new ArrayList<String>();
    rows.add("GRANT USAGE ON *.* TO `" + user + "`@`localhost`");
    byTable.forEach(
        (table, privileges) ->
            rows.add(
                "GRANT "
                    + String.join(", ", privileges)
                    + " ON "
                    + table
                    + " TO `"
                    + user
                    + "`@`localhost`"));
    return rows;
  }

  @Test
  void acceptsExactSets() {
    for (var identity : AdminContentGrantPolicy.Identity.values())
      assertThatCode(
              () ->
                  AdminContentGrantPolicy.validate(
                      identity, ADMIN, PUB, "w@localhost", grants(identity, "w")))
          .doesNotThrowAnyException();
  }

  @Test
  void rejectsExtraMissingWildcardAndForeignRows() {
    var identity = AdminContentGrantPolicy.Identity.PUBLICATION_WRITER;
    var extra = new ArrayList<>(grants(identity, "w"));
    extra.add("GRANT SELECT ON `" + PUB + "`.`catalog_release` TO `w`@`localhost`");
    assertThatThrownBy(
            () -> AdminContentGrantPolicy.validate(identity, ADMIN, PUB, "w@localhost", extra))
        .isInstanceOf(IllegalArgumentException.class);
    var missing = new ArrayList<>(grants(identity, "w"));
    missing.remove(missing.size() - 1);
    assertThatThrownBy(
            () -> AdminContentGrantPolicy.validate(identity, ADMIN, PUB, "w@localhost", missing))
        .isInstanceOf(IllegalArgumentException.class);
    var wildcard = new ArrayList<>(grants(identity, "w"));
    wildcard.add("GRANT SELECT ON `" + PUB + "`.* TO `w`@`localhost`");
    assertThatThrownBy(
            () -> AdminContentGrantPolicy.validate(identity, ADMIN, PUB, "w@localhost", wildcard))
        .isInstanceOf(IllegalArgumentException.class);
    var business = AdminContentGrantPolicy.Identity.BUSINESS_WRITER;
    var crossSchema = new ArrayList<>(grants(business, "w"));
    crossSchema.add("GRANT INSERT ON `" + PUB + "`.`public_work_release` TO `w`@`localhost`");
    assertThatThrownBy(
            () ->
                AdminContentGrantPolicy.validate(business, ADMIN, null, "w@localhost", crossSchema))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AdminContentGrantPolicy.validate(
                    business, ADMIN, null, "other@localhost", grants(business, "w")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
