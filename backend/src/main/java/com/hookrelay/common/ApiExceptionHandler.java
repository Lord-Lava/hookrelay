package com.hookrelay.common;

import com.hookrelay.auth.AuthenticationRequiredException;
import com.hookrelay.endpoint.application.EndpointAlreadyExistsException;
import com.hookrelay.endpoint.application.EndpointNotFoundException;
import com.hookrelay.event.application.IdempotencyConflictException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception) {
    Map<String, String> errors = new LinkedHashMap<>();
    for (FieldError error : exception.getBindingResult().getFieldErrors()) {
      errors.putIfAbsent(error.getField(), error.getDefaultMessage());
    }
    ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Request validation failed");
    problem.setProperty("errors", errors);
    return ResponseEntity.badRequest().body(problem);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException exception) {
    return ResponseEntity.badRequest()
        .body(problem(HttpStatus.BAD_REQUEST, "Request body is not valid JSON"));
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  public ResponseEntity<ProblemDetail> handleIdempotencyConflict(
      IdempotencyConflictException exception) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(problem(HttpStatus.CONFLICT, exception.getMessage()));
  }

  @ExceptionHandler(EndpointNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleEndpointNotFound(EndpointNotFoundException exception) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(problem(HttpStatus.NOT_FOUND, exception.getMessage()));
  }

  @ExceptionHandler(EndpointAlreadyExistsException.class)
  public ResponseEntity<ProblemDetail> handleEndpointAlreadyExists(
      EndpointAlreadyExistsException exception) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(problem(HttpStatus.CONFLICT, exception.getMessage()));
  }

  @ExceptionHandler(AuthenticationRequiredException.class)
  public ResponseEntity<ProblemDetail> handleAuthenticationRequired(
      AuthenticationRequiredException exception) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(problem(HttpStatus.UNAUTHORIZED, exception.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException exception) {
    return ResponseEntity.badRequest()
        .body(problem(HttpStatus.BAD_REQUEST, exception.getMessage()));
  }

  private ProblemDetail problem(HttpStatus status, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create("about:blank"));
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("timestamp", Instant.now());
    return problem;
  }
}
