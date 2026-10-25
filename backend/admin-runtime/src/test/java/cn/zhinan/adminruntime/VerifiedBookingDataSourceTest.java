package cn.zhinan.adminruntime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhinan.adminbooking.policy.AdminBookingGrantPolicy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VerifiedBookingDataSourceTest {
  private static final String SCHEMA = "southleaf_demo";
  private static final String USER = "southleaf_booking_writer@localhost";

  @Test
  void acceptsShowGrantsForExactlyTheDedicatedWriterAndEveryOwnedTable() {
    assertThatCode(() -> VerifiedBookingDataSource.validateGrants(SCHEMA, USER, grants()))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsMissingExtraWildcardDuplicateAndWrongIdentityGrants() {
    var missing = new ArrayList<>(grants());
    missing.remove(1);
    rejected(missing);

    var extra = new ArrayList<>(grants());
    extra.add(
        "GRANT SELECT ON `"
            + SCHEMA
            + "`.`admin_auth_event` TO `southleaf_booking_writer`@`localhost`");
    rejected(extra);

    var wildcard = new ArrayList<>(grants());
    wildcard.add("GRANT SELECT ON `" + SCHEMA + "`.* TO `southleaf_booking_writer`@`localhost`");
    rejected(wildcard);

    var duplicate = new ArrayList<>(grants());
    duplicate.add(duplicate.get(1));
    rejected(duplicate);

    var wrongUser = grants().stream().map(line -> line.replace("@`localhost`", "@`%`")).toList();
    rejected(wrongUser);
  }

  @Test
  void rejectsReaderOnlyTablePrivilegesAndGrantOption() {
    var selectOnly = new ArrayList<String>();
    selectOnly.add(grants().getFirst());
    for (String table : AdminBookingGrantPolicy.OWNED_TABLES)
      selectOnly.add(
          "GRANT SELECT ON `"
              + SCHEMA
              + "`.`"
              + table
              + "` TO `southleaf_booking_writer`@`localhost`");
    rejected(selectOnly);

    var grantOption = new ArrayList<>(grants());
    grantOption.set(1, grantOption.get(1) + " WITH GRANT OPTION");
    rejected(grantOption);

    rejected(List.of("GRANT ALL PRIVILEGES ON *.* TO `southleaf_booking_writer`@`localhost`"));

    var writableFact = new ArrayList<String>();
    for (String line : grants()) if (!line.contains("admin_package_fact")) writableFact.add(line);
    writableFact.add(
        "GRANT ALL PRIVILEGES ON `"
            + SCHEMA
            + "`.`admin_package_fact` TO `southleaf_booking_writer`@`localhost`");
    rejected(writableFact);
  }

  @Test
  void enforcesClosedGrantCountAndSchemaAndExpectedUserShapes() {
    assertThatThrownBy(() -> VerifiedBookingDataSource.validateGrants("Bad-Schema", USER, grants()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> VerifiedBookingDataSource.validateGrants(SCHEMA, "writer@%", grants()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> VerifiedBookingDataSource.validateGrants(SCHEMA, USER, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    var tooMany = new ArrayList<>(grants());
    tooMany.addAll(grants());
    assertThatThrownBy(() -> VerifiedBookingDataSource.validateGrants(SCHEMA, USER, tooMany))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static List<String> grants() {
    var result = new ArrayList<String>();
    result.add("GRANT USAGE ON *.* TO `southleaf_booking_writer`@`localhost`");
    for (String table : AdminBookingGrantPolicy.OWNED_TABLES)
      result.add(
          "GRANT ALL PRIVILEGES ON `"
              + SCHEMA
              + "`.`"
              + table
              + "` TO `southleaf_booking_writer`@`localhost`");
    for (var entry : AdminBookingGrantPolicy.READ_ONLY_GRANTS.entrySet())
      for (String privilege : entry.getValue())
        result.add(
            "GRANT "
                + privilege
                + " ON `"
                + SCHEMA
                + "`.`"
                + entry.getKey()
                + "` TO `southleaf_booking_writer`@`localhost`");
    return result;
  }

  private static void rejected(List<String> grants) {
    assertThatThrownBy(() -> VerifiedBookingDataSource.validateGrants(SCHEMA, USER, grants))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
