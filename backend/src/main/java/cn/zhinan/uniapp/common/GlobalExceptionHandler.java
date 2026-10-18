package cn.zhinan.uniapp.common;

import cn.zhinan.uniapp.dto.PublicDtos;
import cn.zhinan.uniapp.publicquery.PublicQueryException;
import cn.zhinan.uniapp.publicquery.contract.PublicApiResponse;
import cn.zhinan.uniapp.publicquery.port.CatalogStorageException;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
    log.error("Unhandled public request failure type={}", ex.getClass().getName());
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
    log.warn(
        "Public request rejected requestId={} code={} status={}", requestId, code, status.value());
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
