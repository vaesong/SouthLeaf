package cn.zhinan.uniapp.common;

import cn.zhinan.uniapp.booking.PublicBookingException;
import cn.zhinan.uniapp.dto.PublicDtos;
import cn.zhinan.uniapp.publicquery.PublicQueryException;
import cn.zhinan.uniapp.publicquery.contract.PublicApiResponse;
import cn.zhinan.uniapp.publicquery.port.CatalogStorageException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(PublicQueryException.class)
  public ResponseEntity<PublicApiResponse<Object>> publicFailure(PublicQueryException ex) {
    HttpStatus status =
        switch (ex.getErrorCode()) {
          case PUBLIC_QUERY_INVALID -> HttpStatus.BAD_REQUEST;
          case PUBLIC_CURSOR_EXPIRED -> HttpStatus.CONFLICT;
          case PUBLIC_RESOURCE_UNAVAILABLE -> HttpStatus.NOT_FOUND;
          case PUBLIC_SERVICE_UNAVAILABLE, PUBLIC_MEDIA_UNAVAILABLE ->
              HttpStatus.SERVICE_UNAVAILABLE;
          case PUBLIC_RESPONSE_INVALID -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    return error(ex.getErrorCode(), status);
  }

  @ExceptionHandler(CatalogStorageException.class)
  public ResponseEntity<PublicApiResponse<Object>> storageFailure(CatalogStorageException ex) {
    return error(
        PublicQueryException.ErrorCode.PUBLIC_SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE);
  }

  /**
   * The customer booking entry keeps its own vocabulary; a booking failure is never a query error.
   */
  @ExceptionHandler(PublicBookingException.class)
  public ResponseEntity<PublicApiResponse<Object>> bookingFailure(PublicBookingException ex) {
    HttpStatus status =
        switch (ex.getErrorCode()) {
          case REQUEST_INVALID -> HttpStatus.BAD_REQUEST;
          case SESSION_REQUIRED, IDENTITY_REJECTED -> HttpStatus.UNAUTHORIZED;
          case RESOURCE_UNAVAILABLE -> HttpStatus.NOT_FOUND;
          case SLOT_UNAVAILABLE, REQUEST_ALREADY_OPEN -> HttpStatus.CONFLICT;
          case IDENTITY_PROVIDER_UNCONFIGURED, PACKAGE_CATALOG_UNCONFIGURED, SERVICE_UNAVAILABLE ->
              HttpStatus.SERVICE_UNAVAILABLE;
        };
    String requestId = "req_" + UUID.randomUUID().toString().replace("-", "");
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .body(
            PublicApiResponse.error(
                requestId,
                "BOOKING_" + ex.getErrorCode().name(),
                bookingMessage(ex.getErrorCode()),
                new PublicDtos.Meta(Instant.now(), null, null, null)));
  }

  private static String bookingMessage(PublicBookingException.ErrorCode code) {
    return switch (code) {
      case REQUEST_INVALID -> "预约信息无效";
      case SESSION_REQUIRED -> "请先完成微信登录";
      case IDENTITY_REJECTED -> "微信登录失败，请重试";
      case IDENTITY_PROVIDER_UNCONFIGURED -> "预约尚未开放";
      case PACKAGE_CATALOG_UNCONFIGURED -> "套餐暂不可预约";
      case SLOT_UNAVAILABLE -> "该时段已被占用";
      case REQUEST_ALREADY_OPEN -> "你已有一笔待确认的申请";
      case RESOURCE_UNAVAILABLE -> "预约记录不存在";
      case SERVICE_UNAVAILABLE -> "预约服务暂不可用";
    };
  }

  @ExceptionHandler({
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class
  })
  public ResponseEntity<PublicApiResponse<Object>> badRequest(Exception ex) {
    return error(PublicQueryException.ErrorCode.PUBLIC_QUERY_INVALID, HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
  public ResponseEntity<PublicApiResponse<Object>> notFound(Exception ex) {
    return error(PublicQueryException.ErrorCode.PUBLIC_RESOURCE_UNAVAILABLE, HttpStatus.NOT_FOUND);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<PublicApiResponse<Object>> methodNotAllowed(
      HttpRequestMethodNotSupportedException ex) {
    var result =
        error(PublicQueryException.ErrorCode.PUBLIC_QUERY_INVALID, HttpStatus.METHOD_NOT_ALLOWED);
    var headers = new HttpHeaders();
    headers.putAll(result.getHeaders());
    if (ex.getSupportedHttpMethods() != null) headers.setAllow(ex.getSupportedHttpMethods());
    return new ResponseEntity<>(result.getBody(), headers, HttpStatus.METHOD_NOT_ALLOWED);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<PublicApiResponse<Object>> unexpected(Exception ex) {
    return error(
        PublicQueryException.ErrorCode.PUBLIC_RESPONSE_INVALID, HttpStatus.INTERNAL_SERVER_ERROR);
  }

  private ResponseEntity<PublicApiResponse<Object>> error(
      PublicQueryException.ErrorCode code, HttpStatus status) {
    String requestId = "req_" + UUID.randomUUID().toString().replace("-", "");
    String message =
        switch (code) {
          case PUBLIC_QUERY_INVALID -> "请求参数无效";
          case PUBLIC_CURSOR_EXPIRED -> "内容已更新，请重新加载";
          case PUBLIC_RESOURCE_UNAVAILABLE -> "内容当前不可用";
          case PUBLIC_SERVICE_UNAVAILABLE -> "公开服务暂不可用";
          case PUBLIC_MEDIA_UNAVAILABLE -> "公开媒体暂不可用";
          case PUBLIC_RESPONSE_INVALID -> "页面加载异常";
        };
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .body(
            PublicApiResponse.error(
                requestId,
                code.name(),
                message,
                new PublicDtos.Meta(Instant.now(), null, null, null)));
  }
}
