package scripts.testscripts;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

import bio.terra.testrunner.runner.config.TestUserSpecification;
import bio.terra.workspace.api.WorkspaceApi;
import bio.terra.workspace.client.ApiException;
import bio.terra.workspace.model.CreateWorkspaceRequestBody;
import bio.terra.workspace.model.UpdateWorkspaceRequestBody;
import bio.terra.workspace.model.WorkspaceDescription;
import bio.terra.workspace.model.WorkspaceStageModel;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripts.utils.ClientTestUtils;
import scripts.utils.WorkspaceApiTestScriptBase;

public class WorkspaceLifecycle extends WorkspaceApiTestScriptBase {
  private static final Logger logger = LoggerFactory.getLogger(WorkspaceLifecycle.class);

  private static final String WORKSPACE_NAME = "name";
  private static final String WORKSPACE_DESCRIPTION = "description";

  @Override
  public void doUserJourney(TestUserSpecification testUser, WorkspaceApi workspaceApi)
      throws ApiException {
    UUID workspaceUuid = UUID.randomUUID();

    // Perf tests run this test repeatedly. userFacingId needs to be unique for each invocation.
    // Note: These userFacingIds can't be static because UUID.randomUUID() can't be set in a static
    // variable. If a static variable called UUID.randonUUID(), uuid would be the same for some
    // invocations.
    String uuidStr = workspaceUuid.toString();
    String invalidUserFacingId = "User facing id " + uuidStr;
    String validUserFacingId = "user-facing-id-" + uuidStr;
    String validUserFacingId2 = "user-facing-id-2-" + uuidStr;

    CreateWorkspaceRequestBody createBody =
        new CreateWorkspaceRequestBody()
            .id(workspaceUuid)
            .userFacingId(invalidUserFacingId)
            .stage(WorkspaceStageModel.MC_WORKSPACE);

    ApiException ex =
        assertThrows(ApiException.class, () -> workspaceApi.createWorkspace(createBody));
    assertThat(
        ex.getMessage(),
        containsString(
            "ID must have 3-63 characters, contain lowercase letters, numbers, dashes, or underscores, and start with lowercase letter"));

    createBody.userFacingId(validUserFacingId);
    workspaceApi.createWorkspace(createBody);
    ClientTestUtils.assertHttpSuccess(workspaceApi, "CREATE workspace");

    WorkspaceDescription workspaceDescription = workspaceApi.getWorkspace(workspaceUuid);
    ClientTestUtils.assertHttpSuccess(workspaceApi, "GET workspace");
    assertThat(workspaceDescription.getId(), equalTo(workspaceUuid));
    assertThat(workspaceDescription.getStage(), equalTo(WorkspaceStageModel.MC_WORKSPACE));

    UpdateWorkspaceRequestBody updateBody =
        new UpdateWorkspaceRequestBody()
            .userFacingId(invalidUserFacingId)
            .displayName(WORKSPACE_NAME)
            .description(WORKSPACE_DESCRIPTION);
    ex =
        assertThrows(
            ApiException.class, () -> workspaceApi.updateWorkspace(updateBody, workspaceUuid));
    assertThat(
        ex.getMessage(),
        containsString(
            "ID must have 3-63 characters, contain lowercase letters, numbers, dashes, or underscores, and start with lowercase letter"));

    updateBody.userFacingId(validUserFacingId2);
    WorkspaceDescription updatedDescription =
        workspaceApi.updateWorkspace(updateBody, workspaceUuid);
    ClientTestUtils.assertHttpSuccess(workspaceApi, "PATCH workspace");
    assertThat(updatedDescription.getUserFacingId(), equalTo(validUserFacingId2));
    assertThat(updatedDescription.getDisplayName(), equalTo(WORKSPACE_NAME));
    assertThat(updatedDescription.getDescription(), equalTo(WORKSPACE_DESCRIPTION));

    workspaceApi.deleteWorkspace(workspaceUuid);
    ClientTestUtils.assertHttpSuccess(workspaceApi, "DELETE workspace");
  }
}
