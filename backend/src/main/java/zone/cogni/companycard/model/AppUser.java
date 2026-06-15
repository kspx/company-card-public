package zone.cogni.companycard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class AppUser {
    @Id
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private String personUri;

    protected AppUser() {}

    public AppUser(String email, String passwordHash, String role, String personUri) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.personUri = personUri != null ? personUri : "";
    }

    public String email()        { return email; }
    public String passwordHash() { return passwordHash; }
    public String role()         { return role; }
    public String personUri()    { return personUri; }
}
