package bio.terra.workspace.service.workspace.flight.create.azure;

import bio.terra.stairway.FlightContext;
import bio.terra.stairway.Step;
import bio.terra.stairway.StepResult;
import bio.terra.workspace.common.utils.FlightUtils;
import bio.terra.workspace.service.job.JobMapKeys;
import bio.terra.workspace.service.workspace.AzureCloudContextService;
import bio.terra.workspace.service.workspace.model.AzureCloudContext;
import bio.terra.workspace.service.workspace.model.CloudContextHolder;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Updates the previously stored cloud context row, filling in the context JSON. */
public class CreateDbAzureCloudContextFinishStep implements Step {
  private final UUID workspaceUuid;
  private final AzureCloudContextService azureCloudContextService;

  public CreateDbAzureCloudContextFinishStep(
      UUID workspaceUuid, AzureCloudContextService azureCloudContextService) {
    this.workspaceUuid = workspaceUuid;
    this.azureCloudContextService = azureCloudContextService;
  }

  @Override
  public StepResult doStep(FlightContext flightContext) throws InterruptedException {
    AzureCloudContext azureCloudContext =
        flightContext
            .getInputParameters()
            .get(JobMapKeys.REQUEST.getKeyName(), AzureCloudContext.class);

    // Create the cloud context; throws if the context already exists.
    azureCloudContextService.createAzureCloudContextFinish(
        workspaceUuid, azureCloudContext, flightContext.getFlightId());

    CloudContextHolder cch = new CloudContextHolder();
    cch.setAzureCloudContext(azureCloudContext);

    FlightUtils.setResponse(flightContext, cch, HttpStatus.OK);
    return StepResult.getStepResultSuccess();
  }

  @Override
  public StepResult undoStep(FlightContext flightContext) throws InterruptedException {
    // We do not undo anything here. The create step will delete the row, if need be.
    return StepResult.getStepResultSuccess();
  }
}
