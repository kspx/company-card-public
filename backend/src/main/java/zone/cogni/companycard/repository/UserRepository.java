package zone.cogni.companycard.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import zone.cogni.companycard.model.AppUser;

public interface UserRepository extends JpaRepository<AppUser, String> {
}
