package edu.campusai.web;

import edu.campusai.rag.RagException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ApiResult invalidRequest(Exception exception) {
        return ApiResult.error(400, "请求参数或 JSON 格式有误");
    }

    @ExceptionHandler(RagException.class)
    public ApiResult ragError(RagException exception) {
        return ApiResult.error(exception.code(), exception.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ApiResult uploadTooLarge(MaxUploadSizeExceededException exception) {
        return ApiResult.error(400, "资料文件不能超过 10MB");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult> requestFailure(Exception exception) {
        HttpStatusCode status = HttpStatus.INTERNAL_SERVER_ERROR;
        HttpHeaders headers = new HttpHeaders();
        if (exception instanceof ErrorResponse error) {
            status = error.getStatusCode();
            headers.putAll(error.getHeaders());
        }
        String message;
        if (status.is5xxServerError()) {
            log.error("CampusAI 请求处理失败", exception);
            message = "服务暂时不可用，请稍后重试";
        } else {
            message = switch (status.value()) {
                case 404 -> "接口或资源不存在";
                case 405 -> "该接口不支持此请求方法";
                case 415 -> "该接口不支持此请求内容类型";
                default -> "请求无法处理，请核对请求后重试";
            };
        }
        return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_JSON)
                .body(ApiResult.error(status.value(), message));
    }
}
