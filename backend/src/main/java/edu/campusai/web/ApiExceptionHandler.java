package edu.campusai.web;

import edu.campusai.rag.RagException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
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
}
