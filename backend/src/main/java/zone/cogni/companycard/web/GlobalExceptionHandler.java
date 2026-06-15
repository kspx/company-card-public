package zone.cogni.companycard.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import zone.cogni.companycard.exception.OntologyException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(OntologyException.class)
  public ResponseEntity<Map<String, String>> handleOntologyException(OntologyException e) {
    String message = e.getMessage() != null ? e.getMessage() : "Ontology operation failed";
    HttpStatus status = message.contains("not found") ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
    return ResponseEntity.status(status).body(Map.of("error", message));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, String>> handleException(Exception e) {
    log.error("Unexpected error: {}", e.getMessage(), e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "An unexpected error occurred"));
  }
}
