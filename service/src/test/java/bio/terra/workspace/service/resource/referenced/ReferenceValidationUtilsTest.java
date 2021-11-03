package bio.terra.workspace.service.resource.referenced;

import static org.junit.jupiter.api.Assertions.assertThrows;

import bio.terra.workspace.common.BaseUnitTest;
import bio.terra.workspace.service.resource.ValidationUtils;
import bio.terra.workspace.service.resource.referenced.exception.InvalidReferenceException;
import org.junit.jupiter.api.Test;

public class ReferenceValidationUtilsTest extends BaseUnitTest {

  private static final String MAX_VALID_STRING = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final String INVALID_STRING = MAX_VALID_STRING + "b";
  private static final String MAX_VALID_STRING_WITH_DOTS =
      MAX_VALID_STRING + "." + MAX_VALID_STRING + "." + MAX_VALID_STRING + "."
          + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

  @Test
  public void testInvalidCharInBucketName() {
    assertThrows(
        InvalidReferenceException.class,
        () -> ValidationUtils.validateBucketName("INVALIDBUCKETNAME"));
  }

  @Test
  public void validateBucketName_nameHas64Character_throwsException() {
    assertThrows(
        InvalidReferenceException.class,
        () -> ValidationUtils.validateBucketName(INVALID_STRING));
  }

  @Test
  public void validateBucketName_nameHas63Character_OK() {
    ValidationUtils.validateBucketName(MAX_VALID_STRING);
  }

  @Test
  public void validateBucketName_nameHas2Character_throwsException() {
    assertThrows(
        InvalidReferenceException.class,
        () -> ValidationUtils.validateBucketName("aa"));
  }

  @Test
  public void validateBucketName_nameHas3Character_OK() {
    ValidationUtils.validateBucketName("123");
  }

  @Test
  public void validateBucketName_nameHas222CharacterWithDotSeparator_OK() {
    ValidationUtils.validateBucketName(MAX_VALID_STRING_WITH_DOTS);
  }

  @Test
  public void validateBucketName_nameWithDotSeparatorButOneSubstringExceedsLimit_throwsException() {
    assertThrows(
        InvalidReferenceException.class,
        () -> ValidationUtils.validateBucketName(INVALID_STRING + "." + MAX_VALID_STRING));
  }

  @Test
  public void validateBucketName_nameStartAndEndWithNumber_OK() {
    ValidationUtils.validateBucketName("1-bucket-1");
  }

  @Test
  public void validateBucketName_nameStartAndEndWithDot_throwsException() {
    assertThrows(
        InvalidReferenceException.class,
        () -> ValidationUtils.validateBucketName(".bucket-name."));
  }

  @Test
  public void validateBucketName_nameWithGoogPrefix_throwsException() {
    assertThrows(
        InvalidReferenceException.class,
        () -> ValidationUtils.validateBucketName("goog-bucket-name1"));
  }

  @Test
  public void validateBucketName_nameContainsGoogle_throwsException() {
    assertThrows(
        InvalidReferenceException.class,
        () -> ValidationUtils.validateBucketName("bucket-google-name"));
  }

  @Test
  public void validateBucketName_nameContainsG00gle_throwsException() {
    assertThrows(
        InvalidReferenceException.class,
        () -> ValidationUtils.validateBucketName("bucket-g00gle-name"));
  }

  @Test
  public void validBucketNameOk() {
    ValidationUtils.validateBucketName("valid-bucket_name.1");
  }

  @Test
  public void testInvalidCharInBqDatasetName() {
    assertThrows(
        InvalidReferenceException.class,
        () -> ValidationUtils.validateBqDatasetName("invalid-name-for-dataset"));
  }

  @Test
  public void validBqDatasetNameOk() {
    ValidationUtils.validateBqDatasetName("valid_bigquery_name_1");
  }
}
