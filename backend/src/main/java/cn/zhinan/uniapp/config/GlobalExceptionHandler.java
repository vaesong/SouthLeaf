package cn.zhinan.uniapp.config;

import cn.zhinan.uniapp.common.ApiResponse;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ApiResponse<Object> handleIllegalArgumentException(IllegalArgumentException ex) {
        logger.warn("Caught IllegalArgumentException: {}", ex.getMessage());
        return ApiResponse.error(400, "Bad Request: " + ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseBody
    public ApiResponse<Object> handleGeneralException(Exception ex) {
        logger.error("Caught General Exception", ex);
        return ApiResponse.error(500, "Internal Server Error: " + ex.getMessage());
    }
}
