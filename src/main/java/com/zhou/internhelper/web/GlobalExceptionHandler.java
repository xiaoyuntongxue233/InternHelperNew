package com.zhou.internhelper.web;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务参数错误：message 是主动抛出的可读文案，可以返回给前端 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(body(400, e.getMessage() == null ? "参数错误" : e.getMessage()));
    }

    /**
     * Spring MVC 参数绑定/校验类异常：统一 400。
     * 只返回固定中文文案，不透传内部类名、字段名或路径，避免信息泄露。
     */
    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,   // ?size=abc / 路径变量 abc 转 Long
            MissingServletRequestParameterException.class,// 缺少必填查询参数
            HttpMessageNotReadableException.class,        // 请求体非法 JSON
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class
    })
    public ResponseEntity<Map<String, Object>> handleBadBinding(Exception e) {
        log.debug("参数绑定失败：{}", e.getMessage());
        return ResponseEntity.badRequest().body(body(400, "请求参数格式错误"));
    }

    /** 静态资源/路由不存在：404，避免未知路径被兜底成 500 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body(404, "资源不存在"));
    }

    /** 请求方法不支持（如对本为 GET 的接口发 POST）：405，而不是落兜底 500 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.debug("请求方法不支持：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body(405, "请求方法不支持"));
    }

    /**
     * 数据约束冲突兜底：并发创建撞唯一键、或其他路径漏捕获的约束异常，一律 409。
     * 不透传 SQL / 约束名，避免信息泄露。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(DataIntegrityViolationException e) {
        log.warn("数据约束冲突：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body(409, "数据已存在或违反唯一约束"));
    }

    /**
     * 未知异常：响应只给通用文案，避免泄露 SQL / 路径 / 内部实现。
     * 详细信息写入服务端日志，排查看日志即可。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        log.error("未处理异常：{}", e.getClass().getName(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(500, "服务器内部错误，请查看服务端日志"));
    }

    private Map<String, Object> body(int code, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("message", message);
        return map;
    }
}
