package cn.zhinan.admindbpolicy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Exact direct-grant allowlists for the S2-B content writers (D-507 §12.2). Like the security
 * runtime policy this is a complete set comparison, never substring matching or a probe.
 */
public final class AdminContentGrantPolicy {
  public enum Identity {
    BUSINESS_WRITER,
    PUBLICATION_WRITER
  }

  private static final Pattern ROW =
      Pattern.compile(
          "GRANT ([A-Z]+(?:, [A-Z]+)*) ON (\\*|`[A-Za-z0-9_]+`)\\.(\\*|`[A-Za-z0-9_]+`) TO `([A-Za-z][A-Za-z0-9_]{0,31})`@`([A-Za-z0-9._%:/-]{1,255})`");
  private static final Pattern SCHEMA = Pattern.compile("[a-z][a-z0-9_]{0,63}");

  static final Set<String> BUSINESS =
      Set.of(
          "admin:admin_media_asset:SELECT",
          "admin:admin_media_asset:INSERT",
          "admin:admin_media_variant:SELECT",
          "admin:admin_media_variant:INSERT",
          "admin:admin_media_upload_command:SELECT",
          "admin:admin_media_upload_command:INSERT",
          "admin:admin_work_case:SELECT",
          "admin:admin_work_case:INSERT",
          "admin:admin_work_case:UPDATE",
          "admin:admin_work_version:SELECT",
          "admin:admin_work_version:INSERT",
          "admin:admin_work_version_business:SELECT",
          "admin:admin_work_version_business:INSERT",
          "admin:admin_work_version_media:SELECT",
          "admin:admin_work_version_media:INSERT",
          "admin:admin_catalog_draft:SELECT",
          "admin:admin_catalog_draft:INSERT",
          "admin:admin_catalog_draft:UPDATE",
          "admin:admin_catalog_draft_revision:SELECT",
          "admin:admin_catalog_draft_revision:INSERT");

  static final Set<String> PUBLICATION =
      Set.of(
          "admin:admin_work_case:SELECT",
          "admin:admin_work_version:SELECT",
          "admin:admin_work_version_business:SELECT",
          "admin:admin_work_version_media:SELECT",
          "admin:admin_media_asset:SELECT",
          "admin:admin_media_variant:SELECT",
          "admin:admin_work_authorization_gate:SELECT",
          "admin:admin_work_publication_state:SELECT",
          "admin:admin_work_publication_state:INSERT",
          "admin:admin_work_publication_state:UPDATE",
          "admin:admin_publication_command:SELECT",
          "admin:admin_publication_command:INSERT",
          "admin:admin_publication_audit:INSERT",
          "public:public_work_release:INSERT",
          "public:public_work_release_business:INSERT",
          "public:public_work_release_media:INSERT",
          "public:public_work_release_variant:INSERT",
          "public:public_work_release_authorization:INSERT",
          "public:active_work_publication:SELECT",
          "public:active_work_publication:INSERT",
          "public:active_work_publication:UPDATE",
          "public:public_work_projection_epoch:SELECT",
          "public:public_work_projection_epoch:UPDATE",
          "admin:admin_catalog_draft:SELECT",
          "admin:admin_catalog_draft_revision:SELECT",
          "admin:admin_catalog_command:SELECT",
          "admin:admin_catalog_command:INSERT",
          "admin:admin_catalog_audit:INSERT",
          "public:public_catalog_release:INSERT",
          "public:active_catalog_publication:SELECT",
          "public:active_catalog_publication:INSERT",
          "public:active_catalog_publication:UPDATE",
          "public:public_catalog_release_media:INSERT",
          "public:public_catalog_release_variant:INSERT",
          "public:public_catalog_release_binding:INSERT");

  private AdminContentGrantPolicy() {}

  public static Set<String> required(Identity identity) {
    return identity == Identity.BUSINESS_WRITER ? BUSINESS : PUBLICATION;
  }

  public static void validate(
      Identity identity,
      String adminSchema,
      String publicSchema,
      String expectedUser,
      List<String> grants) {
    if (identity == null
        || adminSchema == null
        || expectedUser == null
        || grants == null
        || grants.isEmpty()
        || grants.size() > 64) fail();
    if (identity == Identity.PUBLICATION_WRITER
        && (publicSchema == null
            || !SCHEMA.matcher(publicSchema).matches()
            || publicSchema.equals(adminSchema))) fail();
    Set<String> required = required(identity);
    var found = new HashSet<String>();
    boolean usage = false;
    for (String line : grants) {
      if (line == null || line.length() > 4096) fail();
      var match = ROW.matcher(line);
      if (!match.matches()) fail();
      if (!(match.group(4) + "@" + match.group(5)).equals(expectedUser)) fail();
      String privileges = match.group(1), db = match.group(2), table = match.group(3);
      if (db.equals("*") && table.equals("*") && privileges.equals("USAGE")) {
        if (usage) fail();
        usage = true;
        continue;
      }
      if (table.equals("*")) fail();
      final String scope;
      if (db.equals("`" + adminSchema + "`")) scope = "admin";
      else if (publicSchema != null && db.equals("`" + publicSchema + "`")) scope = "public";
      else {
        fail();
        return;
      }
      String name = table.substring(1, table.length() - 1);
      for (String privilege : privileges.split(", ")) {
        String key = scope + ":" + name + ":" + privilege;
        if (!required.contains(key) || !found.add(key)) fail();
      }
    }
    if (!usage || !found.equals(required)) fail();
  }

  private static void fail() {
    throw new IllegalArgumentException("Content writer grants rejected");
  }
}
