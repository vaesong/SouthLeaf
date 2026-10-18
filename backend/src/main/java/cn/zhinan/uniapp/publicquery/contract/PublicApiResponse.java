package cn.zhinan.uniapp.publicquery.contract;

import cn.zhinan.uniapp.dto.PublicDtos;

/** Stable public response envelope. */
public record PublicApiResponse<T>(
    String requestId, String code, String message, T data, PublicDtos.Meta meta) {

  public static <T> PublicApiResponse<T> success(String requestId, T data, PublicDtos.Meta meta) {
    return new PublicApiResponse<>(requestId, "OK", "", data, meta);
  }

  public static <T> PublicApiResponse<T> error(
      String requestId, String code, String message, PublicDtos.Meta meta) {
    return new PublicApiResponse<>(requestId, code, message, null, meta);
  }
}
