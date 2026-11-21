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
    if (title == null || summary == null || subjectScopeRef == null) invalid("DRAFT_FIELD_INVALID");
    if (title.length() > ContentRules.MAX_TITLE) invalid("DRAFT_TITLE_TOO_LONG");
    if (summary.length() > ContentRules.MAX_SUMMARY) invalid("DRAFT_SUMMARY_TOO_LONG");
    if (!subjectScopeRef.isEmpty() && !ContentRules.scopeRef(subjectScopeRef))
      invalid("DRAFT_SCOPE_INVALID");
    if (businessIds.size() > ContentRules.MAX_BUSINESSES) invalid("DRAFT_BUSINESS_LIMIT");
    if (hasControl(title)) invalid("DRAFT_TITLE_CONTROL");
    if (hasDisallowedSummaryControl(summary)) invalid("DRAFT_SUMMARY_CONTROL");
    var seenBusiness = new HashSet<String>();
    for (String id : businessIds)
      if (!ContentRules.businessId(id) || !seenBusiness.add(id)) invalid("DRAFT_BUSINESS_INVALID");
    var seenMedia = new HashSet<String>();
    for (MediaBinding binding : media) {
      if (binding == null) invalid("DRAFT_MEDIA_INVALID");
      ContentRules.uuid(binding.mediaId());
      if (!seenMedia.add(binding.mediaId())) invalid("DRAFT_MEDIA_DUPLICATE");
    }
    if (coverMediaId != null && !seenMedia.contains(coverMediaId)) invalid("DRAFT_COVER_UNBOUND");
    var cleared = new java.util.ArrayList<MediaBinding>(media.size());
    boolean changed = false;
    for (MediaBinding binding : media) {
      if (!"".equals(binding.altText())) changed = true;
      cleared.add(new MediaBinding(binding.mediaId(), ""));
    }
    // The cover is the first photo. Saving or publishing keeps that photo at position 0.
    int coverAt = indexOf(cleared, coverMediaId);
    if (coverAt > 0) {
      cleared.add(0, cleared.remove(coverAt));
      changed = true;
    }
    if (!changed) return this;
    return new WorkDraft(title, summary, subjectScopeRef, businessIds, cleared, coverMediaId);
  }

  /** Publication completeness (never silently filled with defaults). */
  public void requireComplete() {
    validated();
    if (title.isBlank()
        || summary.isBlank()
        || subjectScopeRef.isEmpty()
        || businessIds.isEmpty()
        || media.isEmpty()
        || coverMediaId == null)
      throw new ContentException(ContentFailure.DRAFT_INCOMPLETE, "DRAFT_INCOMPLETE");
  }

  public List<String> mediaIds() {
    return media.stream().map(MediaBinding::mediaId).toList();
  }

  private static int indexOf(List<MediaBinding> bindings, String mediaId) {
    if (mediaId == null) return -1;
    for (int i = 0; i < bindings.size(); i++)
      if (mediaId.equals(bindings.get(i).mediaId())) return i;
    return -1;
  }

  private static boolean hasControl(String value) {
    return value.chars().anyMatch(c -> c < 0x20 || c == 0x7f);
  }

  /** Introductions may wrap. Other control characters still cannot be stored. */
  private static boolean hasDisallowedSummaryControl(String value) {
    return value
        .chars()
        .anyMatch(c -> c != '\n' && c != '\r' && c != '\t' && (c < 0x20 || c == 0x7f));
  }

  private static void invalid(String reason) {
    throw new ContentException(ContentFailure.INVALID_REQUEST, reason);
  }
}
