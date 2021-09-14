package bio.terra.workspace.service.resource.controlled.flight.clone;

import bio.terra.stairway.FlightContext;
import bio.terra.stairway.Step;
import bio.terra.stairway.StepResult;
import bio.terra.stairway.exception.RetryException;
import bio.terra.workspace.service.iam.AuthenticatedUserRequest;
import bio.terra.workspace.service.iam.model.SamConstants.SamControlledResourceActions;
import bio.terra.workspace.service.resource.controlled.ControlledResource;
import bio.terra.workspace.service.resource.controlled.ControlledResourceMetadataManager;

/**
 * This step validates that the provided user has access to read the provided resource. Unlike other
 * flights, this is handled inside a step instead of before the flight because the containing flight
 * is sometimes launched from within another flight, where it's hard to run pre-flight validation.
 */
public class CheckControlledResourceAuthStep implements Step {

  private final ControlledResource resource;
  private final ControlledResourceMetadataManager controlledResourceMetadataManager;
  private final AuthenticatedUserRequest userRequest;

  public CheckControlledResourceAuthStep(
      ControlledResource resource,
      ControlledResourceMetadataManager controlledResourceMetadataManager,
      AuthenticatedUserRequest userRequest) {
    this.resource = resource;
    this.controlledResourceMetadataManager = controlledResourceMetadataManager;
    this.userRequest = userRequest;
  }

  @Override
  public StepResult doStep(FlightContext context) throws InterruptedException, RetryException {
    // Validate caller can read the source resource before launching flight.
    controlledResourceMetadataManager.validateControlledResourceAndAction(
        userRequest,
        resource.getWorkspaceId(),
        resource.getResourceId(),
        SamControlledResourceActions.READ_ACTION);
    return StepResult.getStepResultSuccess();
  }

  @Override
  public StepResult undoStep(FlightContext context) throws InterruptedException {
    // This step is read-only, so nothing to undo.
    return StepResult.getStepResultSuccess();
  }
}