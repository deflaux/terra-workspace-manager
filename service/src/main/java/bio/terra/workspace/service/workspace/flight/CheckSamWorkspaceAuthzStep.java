package bio.terra.workspace.service.workspace.flight;

import bio.terra.stairway.FlightContext;
import bio.terra.stairway.Step;
import bio.terra.stairway.StepResult;
import bio.terra.stairway.exception.RetryException;
import bio.terra.workspace.db.exception.WorkspaceNotFoundException;
import bio.terra.workspace.service.iam.AuthenticatedUserRequest;
import bio.terra.workspace.service.iam.model.SamConstants;
import bio.terra.workspace.service.workspace.WorkspaceService;
import bio.terra.workspace.service.workspace.model.Workspace;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A step for checking that a user is authorized to read an existing workspace. */
public class CheckSamWorkspaceAuthzStep implements Step {

  private final WorkspaceService workspaceService;
  private final AuthenticatedUserRequest userRequest;
  private final Workspace workspace;

  private final Logger logger = LoggerFactory.getLogger(CreateWorkspaceAuthzStep.class);

  public CheckSamWorkspaceAuthzStep(
      Workspace workspace, WorkspaceService workspaceService, AuthenticatedUserRequest userRequest) {
    this.workspaceService = workspaceService;
    this.userRequest = userRequest;
    this.workspace = workspace;
  }

  @Override
  public StepResult doStep(FlightContext flightContext)
      throws InterruptedException, RetryException {
    UUID workspaceUuid = workspace.getWorkspaceId();
    if (!workspaceService.canReadWorkspace(workspaceUuid, userRequest.getEmail(), userRequest)) {
      throw new WorkspaceNotFoundException(
          String.format(
              "Sam resource not found for workspace %s. WSM requires an existing Sam resource for a RAWLS_WORKSPACE.",
              workspaceUuid));
    }

    return StepResult.getStepResultSuccess();
  }

  @Override
  public StepResult undoStep(FlightContext flightContext) throws InterruptedException {
    return StepResult.getStepResultSuccess();
  }
}
