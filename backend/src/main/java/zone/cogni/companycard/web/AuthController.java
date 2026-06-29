package zone.cogni.companycard.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.StringUtils;
import zone.cogni.companycard.service.UserService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final UserService userService;

  public AuthController(UserService userService) {
    this.userService = userService;
  }

  record LoginRequest(String email, String password) {}

  record RegisterRequest(String email, String password) {}

  record PersonLinkRequest(String email, String personUri) {}

  record CreateUserRequest(String email, String password, String role) {}

  record UpdateRoleRequest(String role) {}

  record ResetPasswordRequest(String password) {}

  @PostMapping("/login")
  public ResponseEntity<?> login(@RequestBody LoginRequest credentials, HttpServletRequest request) {
    String email = credentials.email();
    String password = credentials.password();

    if (!StringUtils.hasText(email) || !StringUtils.hasText(password)) {
      return ResponseEntity.status(401)
                           .body(Map.of("error", "Invalid credentials"));
    }

    return userService.authenticate(email, password)
                      .map(u -> {
                        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                                email, null, List.of(new SimpleGrantedAuthority("ROLE_" + u.role())));
                        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
                        ctx.setAuthentication(auth);
                        SecurityContextHolder.setContext(ctx);

                        HttpSession session = request.getSession(true);
                        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, ctx);

                        return ResponseEntity.ok(Map.of(
                                "role", u.role(),
                                "email", u.email(),
                                "personUri", u.personUri()
                        ));
                      })
                      .orElseGet(() -> ResponseEntity.status(401)
                                                     .body(Map.of("error", "Invalid credentials")));
  }

  @PostMapping("/register")
  public ResponseEntity<?> register(@RequestBody RegisterRequest body) {
    String email = body.email();
    String password = body.password();

    if (!StringUtils.hasText(email) || !StringUtils.hasText(password)) {
      return ResponseEntity.badRequest()
                           .body(Map.of("error", "Email and password are required"));
    }
    if (password.length() < 6) {
      return ResponseEntity.badRequest()
                           .body(Map.of("error", "Password must be at least 6 characters"));
    }

    try {
      userService.createUser(email, password, "USER");
      return ResponseEntity.ok(Map.of("message", "Account created"));
    }
    catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest()
                           .body(Map.of("error", e.getMessage()));
    }
  }

  @PostMapping("/logout")
  public ResponseEntity<?> logout(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();
    return ResponseEntity.ok(Map.of("message", "Logged out"));
  }

  @PatchMapping("/me/person")
  public ResponseEntity<?> linkPersonUri(@RequestBody PersonLinkRequest body) {
    String email = body.email();
    String personUri = body.personUri();

    if (email == null || !StringUtils.hasText(personUri)) {
      return ResponseEntity.badRequest()
                           .body(Map.of("error", "email and personUri are required"));
    }

    try {
      userService.updatePersonUri(email, personUri);
      return ResponseEntity.ok(Map.of("personUri", personUri));
    }
    catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest()
                           .body(Map.of("error", e.getMessage()));
    }
  }

  @PatchMapping("/users/{email}/person")
  public ResponseEntity<?> linkUserPerson(@PathVariable String email, @RequestBody PersonLinkRequest body) {
    String personUri = body.personUri();
    if (personUri == null) {
      return ResponseEntity.badRequest()
                           .body(Map.of("error", "personUri is required"));
    }
    try {
      userService.updatePersonUri(email, personUri);
      return ResponseEntity.ok(Map.of("personUri", personUri));
    }
    catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest()
                           .body(Map.of("error", e.getMessage()));
    }
  }

  @GetMapping("/users")
  public ResponseEntity<?> listUsers() {
    return ResponseEntity.ok(userService.listUsers());
  }

  @PostMapping("/users")
  public ResponseEntity<?> createUser(@RequestBody CreateUserRequest body) {
    try {
      String role = body.role() != null ? body.role() : "USER";
      userService.createUser(body.email(), body.password(), role);
      return ResponseEntity.ok(Map.of("message", "User created"));
    }
    catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest()
                           .body(Map.of("error", e.getMessage()));
    }
  }

  @PatchMapping("/users/{email}")
  public ResponseEntity<?> updateUser(@PathVariable String email, @RequestBody UpdateRoleRequest body) {
    String role = body.role();
    if (role == null || (!role.equals("USER") && !role.equals("ADMIN"))) {
      return ResponseEntity.badRequest()
                           .body(Map.of("error", "role must be USER or ADMIN"));
    }
    try {
      userService.updateRole(email, role);
      return ResponseEntity.ok(Map.of("message", "User updated"));
    }
    catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest()
                           .body(Map.of("error", e.getMessage()));
    }
  }

  @PostMapping("/users/{email}/reset-password")
  public ResponseEntity<?> resetPassword(@PathVariable String email, @RequestBody ResetPasswordRequest body) {
    String password = body.password();
    if (password == null || password.length() < 6) {
      return ResponseEntity.badRequest()
                           .body(Map.of("error", "Password must be at least 6 characters"));
    }
    try {
      userService.resetPassword(email, password);
      return ResponseEntity.ok(Map.of("message", "Password reset"));
    }
    catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest()
                           .body(Map.of("error", e.getMessage()));
    }
  }

  @DeleteMapping("/users/{email}")
  public ResponseEntity<?> deleteUser(@PathVariable String email) {
    boolean deleted = userService.deleteUser(email);
    return deleted
            ? ResponseEntity.ok(Map.of("message", "User deleted"))
            : ResponseEntity.notFound()
                            .build();
  }
}
