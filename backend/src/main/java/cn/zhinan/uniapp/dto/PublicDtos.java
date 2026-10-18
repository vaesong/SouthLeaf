package cn.zhinan.uniapp.dto;

import java.time.Instant;
import java.util.List;

/**
 * Public API DTOs. These records are deliberately separate from persistence entities and contain no
 * customer, authorization evidence, or storage key.
 */
public final class PublicDtos {
  private PublicDtos() {}

  public record Contact(
      String phone, String address, String workingHours, List<ContactChannel> channels) {
    public Contact {
      channels = channels == null ? List.of() : List.copyOf(channels);
    }
  }

  public record ContactChannel(
      String id,
      String platform,
      String label,
      String account,
      Media qrCodeMedia,
      String qrCodeStatus) {}

  public record TeamMember(
      String id,
      String name,
      String role,
      String bio,
      List<String> specialties,
      Media portraitMedia) {
    public TeamMember {
      specialties = specialties == null ? List.of() : List.copyOf(specialties);
    }
  }

  public record Media(
      String id,
      String thumbnailUrl,
      String previewUrl,
      Integer width,
      Integer height,
      String altText,
      Integer sortOrder) {}

  public record Brand(
      String name,
      String englishName,
      String discoveryHeading,
      String shareTitle,
      String tagline,
      String description,
      Media logoMedia,
      Contact contact,
      List<TeamMember> team) {
    public Brand {
      team = team == null ? List.of() : List.copyOf(team);
    }
  }

  public record HeroItem(
      String id,
      String title,
      String subtitle,
      String action,
      Media media,
      String target,
      String targetId) {}

  public record WorkSummary(
      String id,
      String title,
      String summary,
      Media coverMedia,
      List<String> businessLabels,
      Instant publishedAt) {}

  public record WorkDetail(
      String id,
      String title,
      String summary,
      String description,
      List<Media> media,
      List<String> businessLabels,
      String packageLabel,
      Contact contact,
      Instant publishedAt) {}

  public record BusinessSummary(
      String id,
      String name,
      String summary,
      Media coverMedia,
      boolean hasPublishedPackages,
      int packageCount) {}

  public record BusinessDetail(
      String id,
      String name,
      String summary,
      Media coverMedia,
      boolean hasPublishedPackages,
      int packageCount,
      List<PackageSummary> packages) {}

  public record PackageSummary(
      String id,
      String businessId,
      String businessName,
      String name,
      String summary,
      String displayPrice,
      String priceType,
      Media coverMedia) {}

  public record PackageDetail(
      String id,
      String businessId,
      String businessName,
      String name,
      String summary,
      List<String> serviceItems,
      String suitableFor,
      Integer durationMinutes,
      String displayPrice,
      String priceType,
      Media coverMedia,
      List<String> tips,
      List<String> notices,
      Contact contact,
      Instant publishedAt) {}

  public record Home(
      Brand brand,
      List<HeroItem> heroItems,
      List<WorkSummary> featuredWorks,
      List<BusinessSummary> businesses,
      List<PackageSummary> packages,
      Contact contact) {}

  public record Meta(
      Instant serverTime, String contentVersion, String nextCursor, String dataMode) {}
}
