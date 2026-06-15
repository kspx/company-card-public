package zone.cogni.companycard.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import zone.cogni.companycard.model.AppUser;
import zone.cogni.companycard.repository.UserRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class UserService implements UserDetailsService {
  private static final Logger log = LoggerFactory.getLogger(UserService.class);

  private final UserRepository userRepository;
  private final BCryptPasswordEncoder passwordEncoder;
  private final String defaultAdminEmail;
  private final String defaultAdminPassword;

  public UserService(UserRepository userRepository, BCryptPasswordEncoder passwordEncoder,
                     @org.springframework.beans.factory.annotation.Value("${app.admin.email:admin@localhost}") String defaultAdminEmail,
                     @org.springframework.beans.factory.annotation.Value("${app.admin.password:admin}") String defaultAdminPassword) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.defaultAdminEmail = defaultAdminEmail;
    this.defaultAdminPassword = defaultAdminPassword;
  }

  @PostConstruct
  public void seedDefaultAdmin() {
    long count = userRepository.count();
    log.info("UserService seed check: {} users in DB", count);
    if (count == 0) {
      userRepository.save(new AppUser(
        defaultAdminEmail,
        passwordEncoder.encode(defaultAdminPassword),
        "ADMIN",
        ""
      ));
      log.info("Seeded default admin: {}", defaultAdminEmail);
    }
  }

  @Override
  public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
    return userRepository.findById(email)
                         .map(u -> new User(
                           u.email(),
                           u.passwordHash(),
                           List.of(new SimpleGrantedAuthority("ROLE_" + u.role()))
                         ))
                         .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
  }

  public Optional<AppUser> authenticate(String email, String rawPassword) {
    return userRepository.findById(email)
                         .filter(u -> passwordEncoder.matches(rawPassword, u.passwordHash()));
  }

  public List<Map<String, String>> listUsers() {
    return userRepository.findAll()
                         .stream()
                         .map(u -> Map.of(
                           "email", u.email(),
                           "role", u.role(),
                           "personUri", u.personUri()
                         ))
                         .toList();
  }

  public AppUser createUser(String email, String rawPassword, String role) {
    if (userRepository.existsById(email)) {
      throw new IllegalArgumentException("User already exists: " + email);
    }
    return userRepository.save(new AppUser(email, passwordEncoder.encode(rawPassword), role, ""));
  }

  public void updatePersonUri(String email, String personUri) {
    AppUser u = findUser(email);
    userRepository.save(new AppUser(u.email(), u.passwordHash(), u.role(), personUri));
  }

  public void resetPassword(String email, String newRawPassword) {
    AppUser u = findUser(email);
    userRepository.save(new AppUser(u.email(), passwordEncoder.encode(newRawPassword), u.role(), u.personUri()));
  }

  public void updateRole(String email, String role) {
    AppUser u = findUser(email);
    userRepository.save(new AppUser(u.email(), u.passwordHash(), role, u.personUri()));
  }

  private AppUser findUser(String email) {
    return userRepository.findById(email)
                         .orElseThrow(() -> new IllegalArgumentException("User not found: " + email));
  }

  public boolean deleteUser(String email) {
    if (!userRepository.existsById(email)) {
      return false;
    }
    userRepository.deleteById(email);
    return true;
  }
}
