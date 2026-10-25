package cn.zhinan.uniapp.controller;

import cn.zhinan.uniapp.dto.PublicDtos;
import cn.zhinan.uniapp.publicquery.application.PublicQueryService;
import cn.zhinan.uniapp.publicquery.application.PublicQueryService.QueryResult;
import cn.zhinan.uniapp.publicquery.contract.PublicApiResponse;
import cn.zhinan.uniapp.publicquery.contract.PublicPage;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public")
public class PublicQueryController {
  private final PublicQueryService publicQueryService;

  public PublicQueryController(PublicQueryService publicQueryService) {
    this.publicQueryService = publicQueryService;
  }

  @GetMapping("/home")
  public ResponseEntity<PublicApiResponse<PublicDtos.Home>> home() {
    return ok(publicQueryService.readHome());
  }

  @GetMapping("/brand")
  public ResponseEntity<PublicApiResponse<PublicDtos.Brand>> brand() {
    return ok(publicQueryService.readBrand());
  }

  @GetMapping("/works")
  public ResponseEntity<PublicApiResponse<PublicPage<PublicDtos.WorkSummary>>> works(
      @RequestParam(required = false) String businessId,
      @RequestParam(required = false, defaultValue = "recommended") String sort,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String cursor) {
    return ok(publicQueryService.readWorks(businessId, sort, limit, cursor));
  }

  @GetMapping("/works/{workId}")
  public ResponseEntity<PublicApiResponse<PublicDtos.WorkDetail>> work(
      @PathVariable String workId) {
    return ok(publicQueryService.readWork(workId));
  }

  @GetMapping("/businesses")
  public ResponseEntity<PublicApiResponse<PublicPage<PublicDtos.BusinessSummary>>> businesses() {
    return ok(publicQueryService.readBusinesses());
  }

  @GetMapping("/businesses/{businessId}")
  public ResponseEntity<PublicApiResponse<PublicDtos.BusinessDetail>> business(
      @PathVariable String businessId) {
    return ok(publicQueryService.readBusiness(businessId));
  }

  @GetMapping("/packages")
  public ResponseEntity<PublicApiResponse<PublicPage<PublicDtos.PackageSummary>>> packages(
      @RequestParam(required = false) String businessId,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String cursor) {
    return ok(publicQueryService.readPackages(businessId, limit, cursor));
  }

  @GetMapping("/packages/{packageId}")
  public ResponseEntity<PublicApiResponse<PublicDtos.PackageDetail>> packageDetail(
      @PathVariable String packageId) {
    return ok(publicQueryService.readPackage(packageId));
  }

  @GetMapping("/media/{mediaId}/{variant}")
  public ResponseEntity<PublicApiResponse<PublicDtos.Media>> media(
      @PathVariable String mediaId, @PathVariable String variant) {
    return ok(publicQueryService.readMedia(mediaId, variant));
  }

  private <T> ResponseEntity<PublicApiResponse<T>> ok(QueryResult<T> result) {
    String requestId = "req_" + UUID.randomUUID().toString().replace("-", "");
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(PublicApiResponse.success(requestId, result.data(), result.meta()));
  }
}
