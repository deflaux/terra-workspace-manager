package bio.terra.workspace.service.workspace.flight;

import bio.terra.stairway.FlightContext;
import bio.terra.stairway.Step;
import bio.terra.stairway.StepResult;
import bio.terra.stairway.exception.RetryException;
import bio.terra.workspace.service.iam.AuthenticatedUserRequest;
import bio.terra.workspace.service.iam.SamService;
import bio.terra.workspace.service.iam.model.SamConstants;
import bio.terra.workspace.service.workspace.model.Workspace;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A step that creates a Sam workspace resource. This only runs for MC_WORKSPACE stage workspaces,
 * as RAWLS_WORKSPACEs use existing Sam resources instead.
 */
public class CreateWorkspaceAuthzStep implements Step {

  private final SamService samService;
  private final AuthenticatedUserRequest userRequest;
  private final Workspace workspace;

  private final Logger logger = LoggerFactory.getLogger(CreateWorkspaceAuthzStep.class);

  public CreateWorkspaceAuthzStep(
      Workspace workspace, SamService samService, AuthenticatedUserRequest userRequest) {
    this.samService = samService;
    this.userRequest = userRequest;
    this.workspace = workspace;
  }

  @Override
  public StepResult doStep(FlightContext flightContext)
      throws RetryException, InterruptedException {

    // Even though WSM should own this resource, Stairway steps can run multiple times, so it's
    // possible this step already created the resource. If WSM can either read the existing Sam
    // resource or create a new one, this is considered successful.
    if (!canReadExistingWorkspace(workspace.getWorkspaceId())) {
      samService.createWorkspaceWithDefaults(userRequest, workspace.getWorkspaceId());
    }
    return StepResult.getStepResultSuccess();
  }

  @Override
  public StepResult undoStep(FlightContext flightContext) throws InterruptedException {
    samService.deleteWorkspace(userRequest, workspace.getWorkspaceId());
    return StepResult.getStepResultSuccess();
  }

  private boolean canReadExistingWorkspace(UUID workspaceUuid) throws InterruptedException {
    return samService.isAuthorized(
        userRequest,
        SamConstants.SamResource.WORKSPACE,
        workspaceUuid.toString(),
        SamConstants.SamWorkspaceAction.READ);
  }
}
