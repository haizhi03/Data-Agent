package edu.zsc.ai.api.controller.ai;

import edu.zsc.ai.domain.service.ai.model.ModelManagementException;
import edu.zsc.ai.domain.model.dto.response.base.ApiResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(0)
@RestControllerAdvice(assignableTypes=ModelManagementController.class)
public class ModelManagementErrorHandler {
    @ExceptionHandler(ModelManagementException.class)
    public ResponseEntity<ApiResponse<Void>> managed(ModelManagementException e){return ResponseEntity.status(e.status()).body(ApiResponse.error(e.status().value(),e.getMessage()));}
    @ExceptionHandler(edu.zsc.ai.config.ai.model.ModelIntegrationException.class)
    public ResponseEntity<ApiResponse<Void>> model(edu.zsc.ai.config.ai.model.ModelIntegrationException e){return ResponseEntity.status(e.getStatusCode()).body(ApiResponse.error(e.getStatusCode().value(),e.getReason()));}
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> malformed(Exception e, jakarta.servlet.http.HttpServletRequest request){
        if(request.getRequestURI().endsWith("/model-connections/test")) {
            // Use only a known field name, never the Jackson exception message or rejected value (which may be a key).
            for(Throwable cause=e;cause!=null;cause=cause.getCause())if(cause instanceof com.fasterxml.jackson.databind.JsonMappingException mapping) {
                for(var field:mapping.getPath()) {
                    String name=field.getFieldName();
                    String code=name==null?null:switch(name){
                        case "apiStyle","compatibilityProfile" -> "API";
                        case "modelId" -> "MODEL_ID";
                        case "endpoint" -> "ENDPOINT";
                        case "apiKey" -> "KEY";
                        case "provider" -> "PROVIDER";
                        case "expectedVersion" -> "VERSION";
                        default -> null;
                    };
                    if(code!=null)return managed(new ModelManagementException("MODEL_TEST_"+code+"_INVALID",HttpStatus.BAD_REQUEST));
                }
            }
        }
        return managed(ModelManagementException.invalid());
    }
    @ExceptionHandler(cn.dev33.satoken.exception.NotLoginException.class)
    public ResponseEntity<ApiResponse<Void>> unauthenticated(Exception e){return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(401,"MODEL_MANAGEMENT_FORBIDDEN"));}
    @ExceptionHandler({cn.dev33.satoken.exception.NotRoleException.class,cn.dev33.satoken.exception.NotPermissionException.class})
    public ResponseEntity<ApiResponse<Void>> forbidden(Exception e){return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403,"MODEL_MANAGEMENT_FORBIDDEN"));}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception e){return managed(ModelManagementException.unavailable());}
}
