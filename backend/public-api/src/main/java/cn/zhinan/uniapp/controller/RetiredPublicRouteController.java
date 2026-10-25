package cn.zhinan.uniapp.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** Explicitly blocks retired public routes without forwarding them into compatibility code. */
@RestController
class RetiredPublicRouteController {
  @RequestMapping(
      value = {
        "/api/categories/**",
        "/api/packages/**",
        "/api/swipper/**",
        "/public-data/**",
        "/application.properties"
      },
      method = {
        RequestMethod.GET,
        RequestMethod.HEAD,
        RequestMethod.OPTIONS,
        RequestMethod.POST,
        RequestMethod.PUT,
        RequestMethod.PATCH,
        RequestMethod.DELETE
      })
  ResponseEntity<Void> retiredRoute() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore()).build();
  }
}
