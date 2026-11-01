package by.vs.dnaamrscreener.exception;

import by.vs.dnaamrscreener.dto.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import io.lettuce.core.RedisCommandTimeoutException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

   @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(
            IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("bad request: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        String msg = "missing required parameter: " + ex.getParameterName();
        log.warn(msg);
        return build(HttpStatus.BAD_REQUEST, msg, request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String msg = "invalid value for parameter '%s': %s"
                .formatted(ex.getName(), ex.getValue());
        log.warn(msg);
        return build(HttpStatus.BAD_REQUEST, msg, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("validation failed: {}", msg);
        return build(HttpStatus.BAD_REQUEST, msg.isEmpty() ? "validation failed" : msg, request);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiError> handleBindException(
            BindException ex, HttpServletRequest request) {
        String msg = ex.getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("bind validation failed: {}", msg);
        return build(HttpStatus.BAD_REQUEST, msg.isEmpty() ? "validation failed" : msg, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        String msg = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("constraint violation: {}", msg);
        return build(HttpStatus.BAD_REQUEST, msg, request);
    }

    @ExceptionHandler(FileAlreadyProcessingException.class)
    public ResponseEntity<ApiError> handleConflict(
            FileAlreadyProcessingException ex, HttpServletRequest request) {
        log.info("conflict: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler({
            RedisCommandTimeoutException.class,
            SocketTimeoutException.class,
            TimeoutException.class,
            AsyncRequestTimeoutException.class
    })
    public ResponseEntity<ApiError> handleTimeout(
            Exception ex, HttpServletRequest request) {
        log.error("timeout on {}: {}", request.getRequestURI(), ex.toString());
        return build(HttpStatus.GATEWAY_TIMEOUT,
                "request timed out. try a smaller file or retry later.", request);
    }

    @ExceptionHandler(RedisConnectionFailureException.class)
    public ResponseEntity<ApiError> handleRedisDown(
            RedisConnectionFailureException ex, HttpServletRequest request) {
        log.error("redis unavailable", ex);
        return build(HttpStatus.SERVICE_UNAVAILABLE,
                "redis temporarily unavailable", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
            NoResourceFoundException ex, HttpServletRequest request) {
        log.debug("not found: {}", request.getRequestURI());
        return build(HttpStatus.NOT_FOUND, "not found", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneral(
            Exception ex, HttpServletRequest request) {
        log.error("unhandled error on {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                "internal server error", request);
    }

    private String formatFieldError(FieldError fe) {
        return fe.getField() + ": " + fe.getDefaultMessage();
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message,
                                           HttpServletRequest request) {
        return ResponseEntity.status(status).body(
                ApiError.of(
                        status.value(),
                        status.getReasonPhrase(),
                        message,
                        request.getRequestURI()
                )
        );
    }
}