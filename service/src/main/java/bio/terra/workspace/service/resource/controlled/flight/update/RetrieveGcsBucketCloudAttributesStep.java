package bio.terra.workspace.service.resource.controlled.flight.update;

import bio.terra.cloudres.google.storage.BucketCow;
import bio.terra.cloudres.google.storage.StorageCow;
import bio.terra.stairway.FlightContext;
import bio.terra.stairway.FlightMap;
import bio.terra.stairway.Step;
import bio.terra.stairway.StepResult;
import bio.terra.stairway.StepStatus;
import bio.terra.stairway.exception.RetryException;
import bio.terra.workspace.generated.model.ApiGcpGcsBucketUpdateParameters;
import bio.terra.workspace.service.crl.CrlService;
import bio.terra.workspace.service.resource.controlled.ControlledGcsBucketResource;
import bio.terra.workspace.service.resource.controlled.GcsApiConversions;
import bio.terra.workspace.service.workspace.WorkspaceService;
import bio.terra.workspace.service.workspace.flight.WorkspaceFlightMapKeys.ControlledResourceKeys;
import com.google.cloud.storage.BucketInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RetrieveGcsBucketCloudAttributesStep implements Step {
  private static final Logger logger =
      LoggerFactory.getLogger(RetrieveGcsBucketCloudAttributesStep.class);
  private final ControlledGcsBucketResource bucketResource;
  private final CrlService crlService;
  private final WorkspaceService workspaceService;

  public RetrieveGcsBucketCloudAttributesStep(
      ControlledGcsBucketResource bucketResource,
      CrlService crlService,
      WorkspaceService workspaceService) {
    this.bucketResource = bucketResource;
    this.crlService = crlService;
    this.workspaceService = workspaceService;
  }

  @Override
  public StepResult doStep(FlightContext flightContext)
      throws InterruptedException, RetryException {
    final FlightMap workingMap = flightContext.getWorkingMap();
    final String projectId =
        workspaceService.getRequiredGcpProject(bucketResource.getWorkspaceId());
    // get the storage cow
    final StorageCow storageCow = crlService.createStorageCow(projectId);

    // get the existing bucket cow
    final BucketCow existingBucketCow = storageCow.get(bucketResource.getBucketName());
    if (existingBucketCow == null) {
      logger.error(
          "Can't construct COW for pre-existing bucket {}", bucketResource.getBucketName());
      return new StepResult(StepStatus.STEP_RESULT_FAILURE_FATAL, null);
    }

    // get the attributes
    final BucketInfo existingBucketInfo = existingBucketCow.getBucketInfo();
    final ApiGcpGcsBucketUpdateParameters existingUpdateParameters =
        GcsApiConversions.toUpdateParameters(existingBucketInfo);
    workingMap.put(ControlledResourceKeys.PREVIOUS_UPDATE_PARAMETERS, existingUpdateParameters);

    return StepResult.getStepResultSuccess();
  }

  // Nothing to undo here
  @Override
  public StepResult undoStep(FlightContext flightContext) throws InterruptedException {
    return StepResult.getStepResultSuccess();
  }
}