package bio.terra.workspace.service.workspace.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bio.terra.stairway.FlightMap;
import bio.terra.stairway.FlightState;
import bio.terra.stairway.FlightStatus;
import bio.terra.workspace.common.BaseConnectedTest;
import bio.terra.workspace.common.StairwayTestUtils;
import bio.terra.workspace.connected.UserAccessUtils;
import bio.terra.workspace.service.crl.CrlService;
import bio.terra.workspace.service.iam.AuthenticatedUserRequest;
import bio.terra.workspace.service.iam.SamService;
import bio.terra.workspace.service.iam.model.IamRole;
import bio.terra.workspace.service.job.JobMapKeys;
import bio.terra.workspace.service.job.JobService;
import bio.terra.workspace.service.spendprofile.SpendConnectedTestUtils;
import bio.terra.workspace.service.workspace.CloudSyncRoleMapping;
import bio.terra.workspace.service.workspace.WorkspaceCloudContext;
import bio.terra.workspace.service.workspace.WorkspaceService;
import bio.terra.workspace.service.workspace.model.WorkspaceRequest;
import bio.terra.workspace.service.workspace.model.WorkspaceStage;
import com.google.api.services.cloudresourcemanager.model.Binding;
import com.google.api.services.cloudresourcemanager.model.GetIamPolicyRequest;
import com.google.api.services.cloudresourcemanager.model.Policy;
import com.google.api.services.cloudresourcemanager.model.Project;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CreateGoogleContextFlightTest extends BaseConnectedTest {
  /** How long to wait for a Stairway flight to complete before timing out the test. */
  private static final Duration STAIRWAY_FLIGHT_TIMEOUT = Duration.ofMinutes(3);

  @Autowired private WorkspaceService workspaceService;
  @Autowired private CrlService crl;
  @Autowired private JobService jobService;
  @Autowired private SpendConnectedTestUtils spendUtils;
  @Autowired private SamService samService;
  @Autowired private UserAccessUtils userAccessUtils;

  @Test
  void successCreatesProjectAndContext() throws Exception {
    UUID workspaceId = createWorkspace();
    AuthenticatedUserRequest userReq = userAccessUtils.defaultUserAuthRequest();
    assertEquals(
        WorkspaceCloudContext.none(), workspaceService.getCloudContext(workspaceId, userReq));

    FlightState flightState =
        StairwayTestUtils.blockUntilFlightCompletes(
            jobService.getStairway(),
            CreateGoogleContextFlight.class,
            createInputParameters(workspaceId, spendUtils.defaultBillingAccountId(), userReq),
            STAIRWAY_FLIGHT_TIMEOUT);
    assertEquals(FlightStatus.SUCCESS, flightState.getFlightStatus());

    String projectId =
        flightState
            .getResultMap()
            .get()
            .get(WorkspaceFlightMapKeys.GOOGLE_PROJECT_ID, String.class);
    assertEquals(
        WorkspaceCloudContext.builder().googleProjectId(projectId).build(),
        workspaceService.getCloudContext(workspaceId, userReq));
    Project project = crl.getCloudResourceManagerCow().projects().get(projectId).execute();
    assertEquals(projectId, project.getProjectId());
    assertEquals(
        "billingAccounts/" + spendUtils.defaultBillingAccountId(),
        crl.getCloudBillingClientCow()
            .getProjectBillingInfo("projects/" + projectId)
            .getBillingAccountName());
    assertPolicyGroupsSynced(workspaceId, project);
  }

  @Test
  void errorRevertsChanges() throws Exception {
    UUID workspaceId = createWorkspace();
    AuthenticatedUserRequest userReq = userAccessUtils.defaultUserAuthRequest();
    assertEquals(
        WorkspaceCloudContext.none(), workspaceService.getCloudContext(workspaceId, userReq));

    // Submit a flight class that always errors.
    FlightState flightState =
        StairwayTestUtils.blockUntilFlightCompletes(
            jobService.getStairway(),
            ErrorCreateGoogleContextFlight.class,
            createInputParameters(workspaceId, spendUtils.defaultBillingAccountId(), userReq),
            STAIRWAY_FLIGHT_TIMEOUT);
    assertEquals(FlightStatus.ERROR, flightState.getFlightStatus());

    assertEquals(
        WorkspaceCloudContext.none(), workspaceService.getCloudContext(workspaceId, userReq));
    String projectId =
        flightState
            .getResultMap()
            .get()
            .get(WorkspaceFlightMapKeys.GOOGLE_PROJECT_ID, String.class);
    // The Project should exist, but requested to be deleted.
    Project project = crl.getCloudResourceManagerCow().projects().get(projectId).execute();
    assertEquals(projectId, project.getProjectId());
    assertEquals("DELETE_REQUESTED", project.getLifecycleState());
  }

  /** Creates a workspace, returning its workspaceId. */
  private UUID createWorkspace() {
    WorkspaceRequest request =
        WorkspaceRequest.builder()
            .workspaceId(UUID.randomUUID())
            .jobId(UUID.randomUUID().toString())
            .workspaceStage(WorkspaceStage.MC_WORKSPACE)
            .build();
    return workspaceService.createWorkspace(request, userAccessUtils.defaultUserAuthRequest());
  }

  /** Create the FlightMap input parameters required for the {@link CreateGoogleContextFlight}. */
  private static FlightMap createInputParameters(
      UUID workspaceId, String billingAccountId, AuthenticatedUserRequest userReq) {
    FlightMap inputs = new FlightMap();
    inputs.put(WorkspaceFlightMapKeys.WORKSPACE_ID, workspaceId);
    inputs.put(WorkspaceFlightMapKeys.BILLING_ACCOUNT_ID, billingAccountId);
    inputs.put(JobMapKeys.AUTH_USER_INFO.getKeyName(), userReq);
    return inputs;
  }

  /** Asserts that Sam groups are granted their appropriate IAM roles on a GCP project. */
  private void assertPolicyGroupsSynced(UUID workspaceId, Project project) throws Exception {
    Map<IamRole, String> roleToSamGroup =
        Arrays.stream(IamRole.values())
            .collect(
                Collectors.toMap(
                    Function.identity(),
                    role ->
                        "group:"
                            + samService.syncWorkspacePolicy(
                                workspaceId, role, userAccessUtils.defaultUserAuthRequest())));
    Policy currentPolicy =
        crl.getCloudResourceManagerCow()
            .projects()
            .getIamPolicy(project.getProjectId(), new GetIamPolicyRequest())
            .execute();
    for (IamRole role : IamRole.values()) {
      assertRoleBindingsInPolicy(role, roleToSamGroup.get(role), currentPolicy);
    }
  }

  /**
   * Validate that a GCP policy contains expected role bindings.
   *
   * @param role An IAM role. Maps to expected GCP roles in CloudSyncRoleMapping.
   * @param groupEmail The group we expect roles to be bound to.
   * @param gcpPolicy The GCP policy we're checking for role bindings.
   */
  private void assertRoleBindingsInPolicy(IamRole role, String groupEmail, Policy gcpPolicy) {
    List<String> expectedGcpRoleList = CloudSyncRoleMapping.cloudSyncRoleMap.get(role);
    List<Binding> actualGcpBindingList = gcpPolicy.getBindings();
    List<String> actualGcpRoleList =
        actualGcpBindingList.stream().map(Binding::getRole).collect(Collectors.toList());
    for (String gcpRole : expectedGcpRoleList) {
      assertTrue(actualGcpRoleList.contains(gcpRole));
      assertTrue(
          actualGcpBindingList
              .get(actualGcpRoleList.indexOf(gcpRole))
              .getMembers()
              .contains(groupEmail));
    }
  }

  /**
   * An extension of {@link CreateGoogleContextFlight} that has the last step as an error, causing
   * the flight to always attempt to be rolled back.
   */
  public static class ErrorCreateGoogleContextFlight extends CreateGoogleContextFlight {
    public ErrorCreateGoogleContextFlight(FlightMap inputParameters, Object applicationContext) {
      super(inputParameters, applicationContext);
      addStep(new StairwayTestUtils.ErrorDoStep());
    }
  }
}