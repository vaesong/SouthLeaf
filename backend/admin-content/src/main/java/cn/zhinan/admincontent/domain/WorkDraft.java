package cn.zhinan.admincontent.domain;

import java.util.HashSet;
import java.util.List;

/**
 * One immutable draft revision. Fields are restricted to the public whitelist (D-607 §2): title,
 * summary, category codes and media bindings, plus the server-side subject scope reference.
 */
public record WorkDraft(
    String title,
    String summary,
    String subjectScopeRef,
    List<String> businessIds,
    List<MediaBinding> media,
    String coverMediaId) {
  public record MediaBinding(String mediaId, String altText) {}

  public WorkDraft {
    businessIds = List.copyOf(businessIds);
    media = List.copyOf(media);
  }

  public static WorkDraft empty() {
    return new WorkDraft("", "", "", List.of(), List.of(), null);
  }

  /** Shape rules for any saved draft; completeness is checked separately at publication. */
  public WorkDraft validated() {
    if (title == null
        || summary == null
        || subjectScopeRef == null
        || title.length() > ContentRules.MAX_TITLE
        || summary.length() > ContentRules.MAX_SUMMARY
        || (!subjectScopeRef.isEmpty() && !ContentRules.scopeRef(subjectScopeRef))
        || businessIds.size() > ContentRules.MAX_BUSINESSES
        || media.size() > ContentRules.MAX_MEDIA
        || hasControl(title)
        || hasControl(summary)) invalid("DRAFT_FIELD_INVALID");
    var seenBusiness = new HashSet<String>();
    for (String id : businessIds)
      if (!ContentRules.businessId(id) || !seenBusiness.add(id)) invalid("DRAFT_BUSINESS_INVALID");
    var seenMedia = new HashSet<String>();
    for (MediaBinding binding : media) {
      if (binding == null
          || binding.altText() == null
          || binding.altText().length() > ContentRules.MAX_ALT
          || hasControl(binding.altText())) invalid("DRAFT_MEDIA_INVALID");
      ContentRules.uuid(binding.mediaId());
      if (!seenMedia.add(binding.mediaId())) invalid("DRAFT_MEDIA_DUPLICATE");
    }
    if (coverMediaId != null && !seenMedia.contains(coverMediaId)) invalid("DRAFT_COVER_UNBOUND");
    return this;
  }

  /** Publication completeness (never silently filled with defaults). */
  public void requireComplete() {
    validated();
    if (title.isBlank()
        || summary.isBlank()
        || subjectScopeRef.isEmpty()
        || businessIds.isEmpty()
        || media.isEmpty()
        || coverMediaId == null
        || media.stream().anyMatch(m -> m.altText().isBlank()))
      throw new ContentException(ContentFailure.DRAFT_INCOMPLETE, "DRAFT_INCOMPLETE");
  }

  public List<String> mediaIds() {
    return media.stream().map(MediaBinding::mediaId).toList();
  }

  private static boolean hasControl(String value) {
    return value.chars().anyMatch(c -> c < 0x20 || c == 0x7f);
  }

  private static void invalid(String reason) {
    throw new ContentException(ContentFailure.INVALID_REQUEST, reason);
  }
}
