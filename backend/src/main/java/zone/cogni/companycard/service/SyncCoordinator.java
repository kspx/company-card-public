package zone.cogni.companycard.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class SyncCoordinator {
  private static final Logger log = LoggerFactory.getLogger(SyncCoordinator.class);

  private final AtomicReference<String> active = new AtomicReference<>(null);

  public boolean tryRunAsync(String label, Runnable task) {
    if (!active.compareAndSet(null, label)) {
      log.warn("Sync '{}' rejected — '{}' already running", label, active.get());
      return false;
    }
    CompletableFuture.runAsync(() -> {
      long start = System.currentTimeMillis();
      log.info("Sync '{}' started", label);
      try {
        task.run();
      }
      catch (Exception e) {
        log.error("Sync '{}' failed: {}", label, e.getMessage(), e);
      }
      finally {
        log.info("Sync '{}' finished in {}ms", label, System.currentTimeMillis() - start);
        active.set(null);
      }
    });
    return true;
  }

  public String activeSync() {
    return active.get();
  }
}
