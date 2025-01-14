package bio.terra.workspace.common.utils;

import bio.terra.common.exception.ValidationException;
import bio.terra.workspace.generated.model.ApiCloudPlatform;
import bio.terra.workspace.service.workspace.exceptions.CloudPlatformNotImplementedException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Various utilities for validating requests in Controllers. */
public final class ControllerValidationUtils {

  private static final Logger logger = LoggerFactory.getLogger(ControllerValidationUtils.class);

  // Pattern shared with Sam, originally from https://www.regular-expressions.info/email.html.
  public static final Pattern EMAIL_VALIDATION_PATTERN =
      Pattern.compile("(?i)^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$");

  /**
   * Property keys must be 1-1024 characters, using letters, numbers, dashes, and underscores and
   * must not start with a dash or underscore.
   */
  public static final Pattern PROPERTY_KEY_VALIDATION_PATTERN =
      Pattern.compile("^[a-zA-Z0-9][-_a-zA-Z0-9]{0,1023}$");

  /**
   * userFacingId must be 3-63 characters, use lower-case letters, numbers, dashes, or underscores
   * and must start with a letter.
   */
  public static final Pattern USER_FACING_ID_VALIDATION_PATTERN =
      Pattern.compile("^[a-z][-_a-z0-9]{2,62}$");

  /**
   * Utility to validate limit/offset parameters used in pagination.
   *
   * <p>This throws ValidationExceptions if invalid offset or limit values are provided. This only
   * asserts that offset is at least 0 and limit is at least 1. More specific validation can be
   * added for individual endpoints.
   */
  public static void validatePaginationParams(int offset, int limit) {
    List<String> errors = new ArrayList<>();
    if (offset < 0) {
      errors.add("offset must be greater than or equal to 0.");
    }
    if (limit < 1) {
      errors.add("limit must be greater than or equal to 1.");
    }
    if (!errors.isEmpty()) {
      throw new ValidationException("Invalid pagination parameters.", errors);
    }
  }

  /**
   * Validate that a user-provided string matches the format of an email address.
   *
   * <p>This only validates the email addresses format, not whether it exists, what domain it's
   * from, etc.
   */
  public static void validateEmail(String email) {
    // matcher does not support null, so explicitly defend against that case
    if (email == null) {
      logger.warn("User provided null email");
      throw new ValidationException("Missing required email");
    }
    if (!EMAIL_VALIDATION_PATTERN.matcher(email).matches()) {
      logger.warn("User provided invalid email for group or user: " + email);
      throw new ValidationException("Invalid email provided");
    }
  }

  public static void validatePropertyKey(String key) {
    if (key == null) {
      logger.warn("User provided null property key");
      throw new ValidationException("Missing required property key");
    }
    if (!PROPERTY_KEY_VALIDATION_PATTERN.matcher(key).matches()) {
      logger.warn("User provided invalid property key: " + key);
      throw new ValidationException("Invalid property key provided");
    }
  }

  /** Validate that a user is requesting a valid cloud for adding workspace context. */
  public static void validateCloudPlatform(ApiCloudPlatform platform) {
    switch (platform) {
      case GCP:
      case AZURE:
        break;
      default:
        throw new CloudPlatformNotImplementedException(
            "Invalid cloud platform. Currently, only AZURE and GCP are supported.");
    }
  }

  public static void validateUserFacingId(String userFacingId) {
    if (userFacingId == null) {
      logger.warn("userFacingId cannot be null");
      // "ID" instead of "userFacingId" because user sees this.
      throw new ValidationException("ID must be set");
    }
    if (!USER_FACING_ID_VALIDATION_PATTERN.matcher(userFacingId).matches()) {
      logger.warn("User provided invalid userFacingId: " + userFacingId);
      // "ID" instead of "userFacingId" because user sees this.
      throw new ValidationException(
          "ID must have 3-63 characters, contain lowercase letters, numbers, dashes, or underscores, and start with lowercase letter");
    }
  }
}
