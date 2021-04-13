package scripts.testscripts;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import bio.terra.testrunner.runner.config.TestUserSpecification;
import bio.terra.workspace.api.ControlledGcpResourceApi;
import bio.terra.workspace.api.WorkspaceApi;
import bio.terra.workspace.client.ApiException;
import bio.terra.workspace.model.AccessScope;
import bio.terra.workspace.model.CloningInstructionsEnum;
import bio.terra.workspace.model.ControlledResourceCommonFields;
import bio.terra.workspace.model.CreateControlledGcpGcsBucketRequestBody;
import bio.terra.workspace.model.CreatedControlledGcpGcsBucket;
import bio.terra.workspace.model.GcpGcsBucketCreationParameters;
import bio.terra.workspace.model.GcpGcsBucketDefaultStorageClass;
import bio.terra.workspace.model.GcpGcsBucketLifecycle;
import bio.terra.workspace.model.GcpGcsBucketLifecycleRule;
import bio.terra.workspace.model.GcpGcsBucketLifecycleRuleAction;
import bio.terra.workspace.model.GcpGcsBucketLifecycleRuleActionType;
import bio.terra.workspace.model.GcpGcsBucketLifecycleRuleCondition;
import bio.terra.workspace.model.GcpGcsBucketResource;
import bio.terra.workspace.model.JobControl;
import bio.terra.workspace.model.JobReport;
import bio.terra.workspace.model.ManagedBy;
import com.google.api.client.http.HttpStatusCodes;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripts.utils.ClientTestUtils;
import scripts.utils.CloudContextMaker;
import scripts.utils.ResourceMaker;
import scripts.utils.WorkspaceAllocateTestScriptBase;

public class CreateGetDeleteControlledGcsBucket extends WorkspaceAllocateTestScriptBase {
  private static final Logger logger =
      LoggerFactory.getLogger(CreateGetDeleteControlledGcsBucket.class);
  private static final long CREATE_BUCKET_POLL_SECONDS = 5;

  private static final GcpGcsBucketLifecycleRule LIFECYCLE_RULE_1 =
      new GcpGcsBucketLifecycleRule()
          .action(
              new GcpGcsBucketLifecycleRuleAction()
                  .type(
                      GcpGcsBucketLifecycleRuleActionType
                          .DELETE)) // no storage class required for delete actions
          .condition(
              new GcpGcsBucketLifecycleRuleCondition()
                  .age(64)
                  .live(true)
                  .addMatchesStorageClassItem(GcpGcsBucketDefaultStorageClass.ARCHIVE)
                  .numNewerVersions(2));

  private static final GcpGcsBucketLifecycleRule LIFECYCLE_RULE_2 =
      new GcpGcsBucketLifecycleRule()
          .action(
              new GcpGcsBucketLifecycleRuleAction()
                  .storageClass(GcpGcsBucketDefaultStorageClass.NEARLINE)
                  .type(GcpGcsBucketLifecycleRuleActionType.SET_STORAGE_CLASS))
          .condition(
              new GcpGcsBucketLifecycleRuleCondition()
                  .createdBefore(OffsetDateTime.parse("2007-01-03T00:00:00.00Z"))
                  .addMatchesStorageClassItem(GcpGcsBucketDefaultStorageClass.STANDARD));

  // list must not be immutable if deserialization is to work
  static final List<GcpGcsBucketLifecycleRule> LIFECYCLE_RULES =
      new ArrayList<>(List.of(LIFECYCLE_RULE_1, LIFECYCLE_RULE_2));

  private static final String BUCKET_LOCATION = "US-CENTRAL1";
  private static final String BUCKET_PREFIX = "wsmtestbucket-";
  private static final String RESOURCE_PREFIX = "wsmtestresource-";

  private TestUserSpecification reader;
  private String bucketName;
  private String resourceName;

  @Override
  protected void doSetup(List<TestUserSpecification> testUsers, WorkspaceApi workspaceApi)
      throws Exception {
    super.doSetup(testUsers, workspaceApi);
    // Note the 0th user is the owner of the workspace, pulled out in the super class.
    assertThat(
        "There must be at least two test users defined for this test.",
        testUsers != null && testUsers.size() > 1);
    this.reader = testUsers.get(1);
    String nameSuffix = UUID.randomUUID().toString();
    this.bucketName = BUCKET_PREFIX + nameSuffix;
    this.resourceName = RESOURCE_PREFIX + nameSuffix;
  }

  @Override
  public void doUserJourney(TestUserSpecification testUser, WorkspaceApi workspaceApi)
      throws Exception {

    ControlledGcpResourceApi resourceApi =
        ClientTestUtils.getControlledGpcResourceClient(testUser, server);

    // Create a user-shared controlled GCS bucket - should fail due to no cloud context
    CreatedControlledGcpGcsBucket bucket = createBucketAttempt(resourceApi);
    assertThat(bucket.getJobReport().getStatus(), equalTo(JobReport.StatusEnum.FAILED));
    assertThat(
        bucket.getErrorReport().getStatusCode(), equalTo(HttpStatusCodes.STATUS_CODE_BAD_REQUEST));

    // Create the cloud context
    CloudContextMaker.createGcpCloudContext(getWorkspaceId(), workspaceApi);

    // Create the bucket - should work this time
    bucket = createBucketAttempt(resourceApi);
    assertThat(bucket.getJobReport().getStatus(), equalTo(JobReport.StatusEnum.SUCCEEDED));
    UUID resourceId = bucket.getResourceId();

    // Retrieve the bucket resource
    logger.info("Retrieving bucket resource id {}", resourceId.toString());
    GcpGcsBucketResource gotBucket = resourceApi.getBucket(getWorkspaceId(), resourceId);
    assertThat(
        gotBucket.getAttributes().getBucketName(),
        equalTo(bucket.getGcpBucket().getAttributes().getBucketName()));

    // TODO: Check access:
    // - writer can add the file
    // - writer can read the file
    // - reader can read the file
    // - reader cannot write a file
    // - reader cannot delete the bucket

    // Delete bucket
    ResourceMaker.deleteControlledGcsBucket(resourceId, getWorkspaceId(), resourceApi);

    // verify it's not there anymore
    // - via metadata
    // TODO: via GCP access

    try {
      resourceApi.getBucket(getWorkspaceId(), resourceId);
      throw new IllegalStateException("Incorrectly found a deleted bucket!");
    } catch (ApiException ex) {
      assertThat(ex.getCode(), equalTo(HttpStatusCodes.STATUS_CODE_NOT_FOUND));
    }
    bucketName = null;

    // Delete the cloud context. This is not required. Just some exercise for deleteCloudContext
    CloudContextMaker.deleteGcpCloudContext(getWorkspaceId(), workspaceApi);
  }

  private CreatedControlledGcpGcsBucket createBucketAttempt(ControlledGcpResourceApi resourceApi)
      throws Exception {
    String jobId = UUID.randomUUID().toString();
    var creationParameters =
        new GcpGcsBucketCreationParameters()
            .name(bucketName)
            .location(BUCKET_LOCATION)
            .defaultStorageClass(GcpGcsBucketDefaultStorageClass.STANDARD)
            .lifecycle(new GcpGcsBucketLifecycle().rules(LIFECYCLE_RULES));

    var commonParameters =
        new ControlledResourceCommonFields()
            .name(resourceName)
            .cloningInstructions(CloningInstructionsEnum.NOTHING)
            .accessScope(AccessScope.SHARED_ACCESS)
            .managedBy(ManagedBy.USER)
            .jobControl(new JobControl().id(jobId));

    var body =
        new CreateControlledGcpGcsBucketRequestBody()
            .gcsBucket(creationParameters)
            .common(commonParameters);

    logger.info(
        "Attempt to creating bucket {} jobId {} workspace {}", bucketName, jobId, getWorkspaceId());
    CreatedControlledGcpGcsBucket bucket = resourceApi.createBucket(body, getWorkspaceId());
    while (ClientTestUtils.jobIsRunning(bucket.getJobReport())) {
      TimeUnit.SECONDS.sleep(CREATE_BUCKET_POLL_SECONDS);
      bucket = resourceApi.getCreateBucketResult(getWorkspaceId(), jobId);
    }
    logger.info("Create bucket status is {}", bucket.getJobReport().getStatus().toString());
    return bucket;
  }

  @Override
  protected void doCleanup(List<TestUserSpecification> testUsers, WorkspaceApi workspaceApi)
      throws Exception {
    super.doCleanup(testUsers, workspaceApi);
    if (bucketName != null) {
      logger.warn("Test failed to cleanup bucket " + bucketName);
    }
  }
}
