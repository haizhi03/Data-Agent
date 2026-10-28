package edu.zsc.ai.local;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

@RestControllerAdvice
final class LocalRequestAdvice extends RequestBodyAdviceAdapter {
    private final ObjectMapper mapper;
    LocalRequestAdvice(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public boolean supports(MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
        return AbstractJackson2HttpMessageConverter.class.isAssignableFrom(converter);
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type targetType,
                                          Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        // Reject identity hints before DTO deserialization can discard unknown fields.
        byte[] bytes = input.getBody().readNBytes(1_048_577);
        if (bytes.length > 1_048_576) throw LocalRequestException.invalid();
        try {
            LocalScopeGuard.validateBody(mapper.reader()
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes));
        } catch (JsonProcessingException e) {
            throw LocalRequestException.invalid();
        }
        return new HttpInputMessage() {
            @Override public InputStream getBody() { return new ByteArrayInputStream(bytes); }
            @Override public HttpHeaders getHeaders() { return input.getHeaders(); }
        };
    }

    @ExceptionHandler(LocalRequestException.class)
    ResponseEntity<?> localError(LocalRequestException e) {
        return ResponseEntity.status(e.status()).body(e.response());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> malformedBody() { return localError(LocalRequestException.invalid()); }

    @ExceptionHandler(edu.zsc.ai.domain.service.ai.model.ModelManagementException.class)
    ResponseEntity<?> modelConfiguration(edu.zsc.ai.domain.service.ai.model.ModelManagementException e) {
        return localError(new LocalRequestException(e.status().value(), e.getMessage(), e.getMessage()));
    }

    @ExceptionHandler(edu.zsc.ai.config.ai.model.ModelIntegrationException.class)
    ResponseEntity<?> modelRuntime(edu.zsc.ai.config.ai.model.ModelIntegrationException e) {
        return localError(new LocalRequestException(e.getStatusCode().value(), e.getReason(), e.getReason()));
    }

    @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalidFields() { return localError(LocalRequestException.invalid()); }

    @ExceptionHandler({edu.zsc.ai.plugin.connection.ConnectionFailureException.class,
            edu.zsc.ai.domain.exception.BusinessException.class})
    ResponseEntity<?> connectionFailure() {
        // Driver exception text may contain URLs or secrets. Do not echo it to the client.
        return localError(new LocalRequestException(502, "LOCAL_CONNECTION_FAILED", "数据库连接失败，请检查连接配置与驱动"));
    }
}
