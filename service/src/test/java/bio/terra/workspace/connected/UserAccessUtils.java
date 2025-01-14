package bio.terra.workspace.connected;

import bio.terra.workspace.service.iam.AuthenticatedUserRequest;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.collect.ImmutableList;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Helper component for providing user access tokens for connected tests. */
@Component
@Profile("connected-test")
public class UserAccessUtils {
  /** The OAuth scopes important for logging in a user and acting on their behalf in GCP. */
  public static final ImmutableList<String> GCP_SCOPES =
      ImmutableList.of(
          "openid", "email", "profile", "https://www.googleapis.com/auth/cloud-platform");

  /**
   * The path to the service account to use. This service account should be delegated to impersonate
   * users. https://developers.google.com/admin-sdk/directory/v1/guides/delegation
   */
  @Value("${workspace.connected-test.user-delegated-service-account-path}")
  public String userDelegatedServiceAccountPath;

  /** The email address of a default user to use for testing. */
  @Value("${workspace.connected-test.default-user-email}")
  private String defaultUserEmail;

  /**
   * The email address of a second, not-the-default user to use for testing. Useful for tests that
   * require two valid users.
   */
  @Value("${workspace.connected-test.second-user-email}")
  private String secondUserEmail;

  /** Creates Google credentials for the user. Relies on domain delegation. */
  public GoogleCredentials generateCredentials(String userEmail) {
    try {
      GoogleCredentials credentials =
          GoogleCredentials.fromStream(new FileInputStream(userDelegatedServiceAccountPath))
              .createScoped(GCP_SCOPES)
              .createDelegated(userEmail);
      credentials.refreshIfExpired();
      return credentials;
    } catch (IOException e) {
      throw new RuntimeException("Error creating GoogleCredentials for user " + userEmail, e);
    }
  }

  /** Generates an OAuth access token for the userEmail. Relies on domain delegation. */
  public AccessToken generateAccessToken(String userEmail) {
    return generateCredentials(userEmail).getAccessToken();
  }

  /** Creates a {@link TestUser} for the default test user. */
  public TestUser defaultUser() {
    return new TestUser(defaultUserEmail);
  }

  /** Generates an OAuth access token for the default test user. */
  public AccessToken defaultUserAccessToken() {
    return generateAccessToken(defaultUserEmail);
  }

  /** Generates an OAuth access token for the second test user. */
  public AccessToken secondUserAccessToken() {
    return generateAccessToken(secondUserEmail);
  }

  /** Expose the default test user email. */
  public String getDefaultUserEmail() {
    return defaultUserEmail;
  }

  /** Expose the second test user email. */
  public String getSecondUserEmail() {
    return secondUserEmail;
  }

  /** Provides an AuthenticatedUserRequest using the default user's email and access token. */
  public AuthenticatedUserRequest defaultUserAuthRequest() {
    return new AuthenticatedUserRequest()
        .email(getDefaultUserEmail())
        .token(Optional.of(defaultUserAccessToken().getTokenValue()));
  }

  /** Provides an AuthenticatedUserRequest using the second user's email and access token. */
  public AuthenticatedUserRequest secondUserAuthRequest() {
    return new AuthenticatedUserRequest()
        .email(getSecondUserEmail())
        .token(Optional.of(secondUserAccessToken().getTokenValue()));
  }

  /**
   * Represents a test user with convenience method to impersonate them in different forms.
   *
   * <p>This will only work for test user emails that can have delegated credentials.
   */
  public class TestUser {
    private final String email;

    public TestUser(String email) {
      this.email = email;
    }

    public String getEmail() {
      return email;
    }

    public GoogleCredentials getGoogleCredentials() {
      return generateCredentials(email);
    }

    public AccessToken getAccessToken() {
      return generateAccessToken(email);
    }

    public AuthenticatedUserRequest getAuthenticatedRequest() {
      return new AuthenticatedUserRequest()
          .email(email)
          .token(Optional.of(getAccessToken().getTokenValue()));
    }
  }
}
