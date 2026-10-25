package cn.zhinan.uniapp.controller;

import cn.zhinan.uniapp.media.application.PublicMediaService;
import cn.zhinan.uniapp.publicquery.PublicQueryException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/public/media")
public class PublicMediaContentController {
  private final PublicMediaService reader;

  public PublicMediaContentController(PublicMediaService reader) {
    this.reader = reader;
  }

  @GetMapping("/{mediaId}/{variant}/content")
  public ResponseEntity<byte[]> read(
      @PathVariable String mediaId,
      @PathVariable String variant,
      @RequestHeader(value = "Range", required = false) String range) {
    if (!mediaId.matches("[A-Za-z0-9_-]{1,128}")
        || !java.util.List.of("thumbnail", "preview").contains(variant)) {
      throw new PublicQueryException(
          PublicQueryException.ErrorCode.PUBLIC_QUERY_INVALID, "Invalid media query");
    }
    // Range and conditional headers cannot select a cached authorization result.
    var content = reader.read(mediaId, variant);
    if (range != null)
      return ResponseEntity.status(416).cacheControl(CacheControl.noStore()).build();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .contentType(MediaType.parseMediaType(content.contentType()))
        .contentLength(content.bytes().length)
        .body(content.bytes());
  }
}
