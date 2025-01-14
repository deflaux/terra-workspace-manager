package bio.terra.workspace.common.utils;

import bio.terra.cloudres.google.api.services.common.OperationCow;
import bio.terra.cloudres.google.api.services.common.OperationUtils;
import bio.terra.cloudres.google.cloudresourcemanager.CloudResourceManagerCow;
import bio.terra.stairway.Step;
import bio.terra.stairway.exception.RetryException;
import bio.terra.workspace.service.workspace.exceptions.SaCredentialsMissingException;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.cloudresourcemanager.v3.model.Project;
import com.google.auth.ServiceAccountSigner;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.ServiceOptions;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;

/** Utilities for interacting with Google Cloud APIs within {@link Step}s. */
public class GcpUtils {
  private GcpUtils() {}

  /** Try to delete the Project associated with {@code projectId}. */
  public static void deleteProject(String projectId, CloudResourceManagerCow resourceManager)
      throws IOException, InterruptedException, RetryException {
    Optional<Project> project = retrieveProject(projectId, resourceManager);
    if (project.isEmpty()) {
      // The project does not exist.
      return;
    }
    if (project.get().getState().equals("DELETE_REQUESTED")
        || project.get().getState().equals("DELETE_IN_PROGRESS")) {
      // The project is already being deleted.
      return;
    }
    pollUntilSuccess(
        resourceManager
            .operations()
            .operationCow(resourceManager.projects().delete(projectId).execute()),
        Duration.ofSeconds(5),
        Duration.ofMinutes(5));
  }

  /**
   * Returns a {@link Project} corresponding to the {@code projectId}, if one exists. Handles 403
   * errors as no project existing.
   */
  public static Optional<Project> retrieveProject(
      String projectId, CloudResourceManagerCow resourceManager) throws IOException {
    try {
      return Optional.of(resourceManager.projects().get(projectId).execute());
    } catch (GoogleJsonResponseException e) {
      if (e.getStatusCode() == HttpStatus.FORBIDDEN.value()) {
        // Google returns 403 for projects we don't have access to and projects that don't exist.
        // We assume in this case that the project does not exist, not that somebody else has
        // created a project with the same id.
        return Optional.empty();
      }
      throw e;
    }
  }

  /**
   * Poll until the Google Service API operation has completed. Throws any error or timeouts as a
   * {@link RetryException}.
   */
  public static void pollUntilSuccess(
      OperationCow<?> operation, Duration pollingInterval, Duration timeout)
      throws RetryException, IOException, InterruptedException {
    operation = OperationUtils.pollUntilComplete(operation, pollingInterval, timeout);
    if (operation.getOperationAdapter().getError() != null) {
      throw new RetryException(
          String.format(
              "Error polling operation. name [%s] message [%s]",
              operation.getOperationAdapter().getName(),
              operation.getOperationAdapter().getError().getMessage()));
    }
  }

  /**
   * Retry a supplier method until the value supplied equals an expected value. Useful for verifying
   * a set value has propagated cloudward and may now be relied upon.
   *
   * @param expected - expected (previously set on cloud) value
   * @param supplier - function to get the value from the cloud
   * @param retryInterval - time to sleep between retries
   * @param maxRetries - maximum number of times to retry the supplier
   * @param <T> - type of expected and supplier specialization
   */
  public static <T> void pollUntilEqual(
      T expected, Supplier<T> supplier, Duration retryInterval, int maxRetries) {
    T actual = supplier.get();
    try {
      int retryAttempts = 0;
      while (!actual.equals(expected) && retryAttempts++ < maxRetries) {
        TimeUnit.MILLISECONDS.sleep(retryInterval.toMillis());
        actual = supplier.get();
      }
    } catch (InterruptedException e) {
      throw new RuntimeException("Interrupted while verifying set value.", e);
    }
  }

  public static String getControlPlaneProjectId() {
    return Optional.ofNullable(ServiceOptions.getDefaultProjectId())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Could not determine default GCP control plane project ID."));
  }

  /**
   * Returns the email of the application default credentials, which should represent a service
   * account in all WSM deployments.
   */
  public static String getWsmSaEmail(GoogleCredentials wsmCredentials) {
    // WSM always runs as a service account, but credentials for that SA may come from different
    // sources depending on whether it is running in GCP or locally.
    if (wsmCredentials instanceof ServiceAccountSigner) {
      return ((ServiceAccountSigner) wsmCredentials).getAccount();
    } else {
      throw new SaCredentialsMissingException(
          "Unable to find WSM service account credentials. Ensure WSM is actually running as a service account");
    }
  }

  public static String getWsmSaEmail() {
    try {
      return getWsmSaEmail(GoogleCredentials.getApplicationDefault());
    } catch (IOException e) {
      throw new SaCredentialsMissingException(
          "Unable to find WSM service account credentials. Ensure WSM is actually running as a service account");
    }
  }
}
