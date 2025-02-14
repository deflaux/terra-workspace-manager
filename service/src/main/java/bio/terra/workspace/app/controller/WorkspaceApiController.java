package bio.terra.workspace.app.controller;

import bio.terra.workspace.common.utils.ControllerValidationUtils;
import bio.terra.workspace.generated.controller.WorkspaceApi;
import bio.terra.workspace.generated.model.ApiAzureContext;
import bio.terra.workspace.generated.model.ApiCloneWorkspaceRequest;
import bio.terra.workspace.generated.model.ApiCloneWorkspaceResult;
import bio.terra.workspace.generated.model.ApiClonedWorkspace;
import bio.terra.workspace.generated.model.ApiCloudPlatform;
import bio.terra.workspace.generated.model.ApiCreateCloudContextRequest;
import bio.terra.workspace.generated.model.ApiCreateCloudContextResult;
import bio.terra.workspace.generated.model.ApiCreateWorkspaceRequestBody;
import bio.terra.workspace.generated.model.ApiCreatedWorkspace;
import bio.terra.workspace.generated.model.ApiGcpContext;
import bio.terra.workspace.generated.model.ApiGrantRoleRequestBody;
import bio.terra.workspace.generated.model.ApiIamRole;
import bio.terra.workspace.generated.model.ApiJobReport.StatusEnum;
import bio.terra.workspace.generated.model.ApiProperties;
import bio.terra.workspace.generated.model.ApiProperty;
import bio.terra.workspace.generated.model.ApiRoleBinding;
import bio.terra.workspace.generated.model.ApiRoleBindingList;
import bio.terra.workspace.generated.model.ApiUpdateWorkspaceRequestBody;
import bio.terra.workspace.generated.model.ApiWorkspaceDescription;
import bio.terra.workspace.generated.model.ApiWorkspaceDescriptionList;
import bio.terra.workspace.generated.model.ApiWorkspaceStageModel;
import bio.terra.workspace.service.iam.AuthenticatedUserRequest;
import bio.terra.workspace.service.iam.AuthenticatedUserRequestFactory;
import bio.terra.workspace.service.iam.SamRethrow;
import bio.terra.workspace.service.iam.SamService;
import bio.terra.workspace.service.iam.exception.InvalidRoleException;
import bio.terra.workspace.service.iam.model.SamConstants;
import bio.terra.workspace.service.iam.model.WsmIamRole;
import bio.terra.workspace.service.job.JobService;
import bio.terra.workspace.service.job.JobService.AsyncJobResult;
import bio.terra.workspace.service.petserviceaccount.PetSaService;
import bio.terra.workspace.service.spendprofile.SpendProfileId;
import bio.terra.workspace.service.workspace.AzureCloudContextService;
import bio.terra.workspace.service.workspace.GcpCloudContextService;
import bio.terra.workspace.service.workspace.WorkspaceService;
import bio.terra.workspace.service.workspace.exceptions.CloudContextRequiredException;
import bio.terra.workspace.service.workspace.model.AzureCloudContext;
import bio.terra.workspace.service.workspace.model.CloudContextHolder;
import bio.terra.workspace.service.workspace.model.GcpCloudContext;
import bio.terra.workspace.service.workspace.model.Workspace;
import bio.terra.workspace.service.workspace.model.WorkspaceStage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

@Controller
public class WorkspaceApiController extends ControllerBase implements WorkspaceApi {
  private static final Logger logger = LoggerFactory.getLogger(WorkspaceApiController.class);
  private final WorkspaceService workspaceService;
  private final JobService jobService;
  private final SamService samService;
  private final AzureCloudContextService azureCloudContextService;
  private final GcpCloudContextService gcpCloudContextService;
  private final PetSaService petSaService;

  @Autowired
  public WorkspaceApiController(
      WorkspaceService workspaceService,
      JobService jobService,
      SamService samService,
      AuthenticatedUserRequestFactory authenticatedUserRequestFactory,
      HttpServletRequest request,
      GcpCloudContextService gcpCloudContextService,
      PetSaService petSaService,
      AzureCloudContextService azureCloudContextService) {
    super(authenticatedUserRequestFactory, request, samService);
    this.workspaceService = workspaceService;
    this.jobService = jobService;
    this.samService = samService;
    this.azureCloudContextService = azureCloudContextService;
    this.gcpCloudContextService = gcpCloudContextService;
    this.petSaService = petSaService;
  }

  @Override
  public ResponseEntity<ApiCreatedWorkspace> createWorkspace(
      @RequestBody ApiCreateWorkspaceRequestBody body) {
    AuthenticatedUserRequest userRequest = getAuthenticatedInfo();
    logger.info(
        "Creating workspace {} for {} subject {}",
        body.getId(),
        userRequest.getEmail(),
        userRequest.getSubjectId());

    // Existing client libraries should not need to know about the stage, as they won't use any of
    // the features it gates. If stage isn't specified in a create request, we default to
    // RAWLS_WORKSPACE.
    ApiWorkspaceStageModel requestStage = body.getStage();
    requestStage = (requestStage == null ? ApiWorkspaceStageModel.RAWLS_WORKSPACE : requestStage);
    WorkspaceStage internalStage = WorkspaceStage.fromApiModel(requestStage);
    Optional<SpendProfileId> spendProfileId =
        Optional.ofNullable(body.getSpendProfile()).map(SpendProfileId::new);

    // ET uses userFacingId; CWB doesn't. Schema enforces that userFacingId must be set. CWB doesn't
    // pass userFacingId in request, so use id. Prefix with "a" because userFacingId must start with
    // a letter.
    String userFacingId = Optional.ofNullable(body.getUserFacingId()).orElse("a-" + body.getId());
    ControllerValidationUtils.validateUserFacingId(userFacingId);

    Workspace workspace =
        Workspace.builder()
            .workspaceId(body.getId())
            .userFacingId(userFacingId)
            .displayName(body.getDisplayName())
            .description(body.getDescription())
            .spendProfileId(spendProfileId.orElse(null))
            .workspaceStage(internalStage)
            .properties(propertyMapFromApi(body.getProperties()))
            .build();
    UUID createdId = workspaceService.createWorkspace(workspace, userRequest);

    ApiCreatedWorkspace responseWorkspace = new ApiCreatedWorkspace().id(createdId);
    logger.info("Created workspace {} for {}", responseWorkspace, userRequest.getEmail());

    return new ResponseEntity<>(responseWorkspace, HttpStatus.OK);
  }

  @Override
  public ResponseEntity<ApiWorkspaceDescriptionList> listWorkspaces(Integer offset, Integer limit) {
    AuthenticatedUserRequest userRequest = getAuthenticatedInfo();
    logger.info("Listing workspaces for {}", userRequest.getEmail());
    ControllerValidationUtils.validatePaginationParams(offset, limit);
    List<Workspace> workspaces = workspaceService.listWorkspaces(userRequest, offset, limit);
    var response =
        new ApiWorkspaceDescriptionList()
            .workspaces(
                workspaces.stream()
                    .map(this::buildWorkspaceDescription)
                    .collect(Collectors.toList()));
    return new ResponseEntity<>(response, HttpStatus.OK);
  }

  private ApiWorkspaceDescription buildWorkspaceDescription(Workspace workspace) {
    ApiGcpContext gcpContext =
        gcpCloudContextService
            .getGcpCloudContext(workspace.getWorkspaceId())
            .map(GcpCloudContext::toApi)
            .orElse(null);

    ApiAzureContext azureContext =
        azureCloudContextService
            .getAzureCloudContext(workspace.getWorkspaceId())
            .map(AzureCloudContext::toApi)
            .orElse(null);

    // Convert the property map to API format
    ApiProperties apiProperties = new ApiProperties();
    workspace
        .getProperties()
        .forEach((k, v) -> apiProperties.add(new ApiProperty().key(k).value(v)));

    // When we have another cloud context, we will need to do a similar retrieval for it.
    return new ApiWorkspaceDescription()
        .id(workspace.getWorkspaceId())
        .userFacingId(workspace.getUserFacingId())
        .displayName(workspace.getDisplayName().orElse(null))
        .description(workspace.getDescription().orElse(null))
        .properties(apiProperties)
        .spendProfile(workspace.getSpendProfileId().map(SpendProfileId::getId).orElse(null))
        .stage(workspace.getWorkspaceStage().toApiModel())
        .gcpContext(gcpContext)
        .azureContext(azureContext);
  }

  @Override
  public ResponseEntity<ApiWorkspaceDescription> getWorkspace(
      @PathVariable("workspaceId") UUID uuid) {
    AuthenticatedUserRequest userRequest = getAuthenticatedInfo();
    logger.info("Getting workspace {} for {}", uuid, userRequest.getEmail());
    Workspace workspace = workspaceService.getWorkspace(uuid, userRequest);
    ApiWorkspaceDescription desc = buildWorkspaceDescription(workspace);
    logger.info("Got workspace {} for {}", desc, userRequest.getEmail());

    return new ResponseEntity<>(desc, HttpStatus.OK);
  }

  @Override
  public ResponseEntity<ApiWorkspaceDescription> updateWorkspace(
      @PathVariable("workspaceId") UUID workspaceUuid,
      @RequestBody ApiUpdateWorkspaceRequestBody body) {
    AuthenticatedUserRequest userRequest = getAuthenticatedInfo();
    logger.info("Updating workspace {} for {}", workspaceUuid, userRequest.getEmail());

    Map<String, String> propertyMap = null;
    if (body.getProperties() != null) {
      propertyMap = propertyMapFromApi(body.getProperties());
    }

    if (body.getUserFacingId() != null) {
      ControllerValidationUtils.validateUserFacingId(body.getUserFacingId());
    }

    Workspace workspace =
        workspaceService.updateWorkspace(
            userRequest,
            workspaceUuid,
            body.getUserFacingId(),
            body.getDisplayName(),
            body.getDescription(),
            propertyMap);

    ApiWorkspaceDescription desc = buildWorkspaceDescription(workspace);
    logger.info("Updated workspace {} for {}", desc, userRequest.getEmail());

    return new ResponseEntity<>(desc, HttpStatus.OK);
  }

  @Override
  public ResponseEntity<Void> deleteWorkspace(@PathVariable("workspaceId") UUID uuid) {
    AuthenticatedUserRequest userRequest = getAuthenticatedInfo();
    logger.info("Deleting workspace {} for {}", uuid, userRequest.getEmail());
    workspaceService.deleteWorkspace(uuid, userRequest);
    logger.info("Deleted workspace {} for {}", uuid, userRequest.getEmail());

    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  @Override
  public ResponseEntity<ApiWorkspaceDescription> getWorkspaceByUserFacingId(
      @PathVariable("workspaceUserFacingId") String userFacingId) {
    AuthenticatedUserRequest userRequest = getAuthenticatedInfo();
    logger.info("Getting workspace {} for {}", userFacingId, userRequest.getEmail());
    Workspace workspace = workspaceService.getWorkspaceByUserFacingId(userFacingId, userRequest);
    ApiWorkspaceDescription desc = buildWorkspaceDescription(workspace);
    logger.info("Got workspace {} for {}", desc, userRequest.getEmail());

    return new ResponseEntity<>(desc, HttpStatus.OK);
  }

  @Override
  public ResponseEntity<Void> grantRole(
      @PathVariable("workspaceId") UUID uuid,
      @PathVariable("role") ApiIamRole role,
      @RequestBody ApiGrantRoleRequestBody body) {
    ControllerValidationUtils.validateEmail(body.getMemberEmail());
    if (role == ApiIamRole.APPLICATION) {
      throw new InvalidRoleException(
          "Users cannot grant role APPLICATION. Use application registration instead.");
    }
    SamRethrow.onInterrupted(
        () ->
            samService.grantWorkspaceRole(
                uuid, getAuthenticatedInfo(), WsmIamRole.fromApiModel(role), body.getMemberEmail()),
        "grantWorkspaceRole");
    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  @Override
  public ResponseEntity<Void> removeRole(
      @PathVariable("workspaceId") UUID uuid,
      @PathVariable("role") ApiIamRole role,
      @PathVariable("memberEmail") String memberEmail) {
    ControllerValidationUtils.validateEmail(memberEmail);
    if (role == ApiIamRole.APPLICATION) {
      throw new InvalidRoleException(
          "Users cannot remove role APPLICATION. Use application registration instead.");
    }
    AuthenticatedUserRequest userRequest = getAuthenticatedInfo();
    workspaceService.removeWorkspaceRoleFromUser(
        uuid, WsmIamRole.fromApiModel(role), memberEmail, userRequest);
    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  @Override
  public ResponseEntity<ApiRoleBindingList> getRoles(@PathVariable("workspaceId") UUID uuid) {

    List<bio.terra.workspace.service.iam.model.RoleBinding> bindingList =
        SamRethrow.onInterrupted(
            () -> samService.listRoleBindings(uuid, getAuthenticatedInfo()), "listRoleBindings");
    ApiRoleBindingList responseList = new ApiRoleBindingList();
    for (bio.terra.workspace.service.iam.model.RoleBinding roleBinding : bindingList) {
      responseList.add(
          new ApiRoleBinding().role(roleBinding.role().toApiModel()).members(roleBinding.users()));
    }
    return new ResponseEntity<>(responseList, HttpStatus.OK);
  }

  @Override
  public ResponseEntity<ApiCreateCloudContextResult> createCloudContext(
      UUID uuid, @Valid ApiCreateCloudContextRequest body) {
    ControllerValidationUtils.validateCloudPlatform(body.getCloudPlatform());
    AuthenticatedUserRequest userRequest = getAuthenticatedInfo();
    String jobId = body.getJobControl().getId();
    String resultPath = getAsyncResultEndpoint(jobId);

    if (body.getCloudPlatform() == ApiCloudPlatform.AZURE) {
      ApiAzureContext azureContext =
          Optional.ofNullable(body.getAzureContext())
              .orElseThrow(
                  () ->
                      new CloudContextRequiredException(
                          "AzureContext is required when creating an azure cloud context for a workspace"));
      workspaceService.createAzureCloudContext(
          uuid, jobId, userRequest, resultPath, AzureCloudContext.fromApi(azureContext));
    } else {
      workspaceService.createGcpCloudContext(uuid, jobId, userRequest, resultPath);
    }

    ApiCreateCloudContextResult response = fetchCreateCloudContextResult(jobId, userRequest);
    return new ResponseEntity<>(response, getAsyncResponseCode(response.getJobReport()));
  }

  @Override
  public ResponseEntity<ApiCreateCloudContextResult> getCreateCloudContextResult(
      UUID uuid, String jobId) {
    AuthenticatedUserRequest userRequest = getAuthenticatedInfo();
    ApiCreateCloudContextResult response = fetchCreateCloudContextResult(jobId, userRequest);
    return new ResponseEntity<>(response, getAsyncResponseCode(response.getJobReport()));
  }

  private ApiCreateCloudContextResult fetchCreateCloudContextResult(
      String jobId, AuthenticatedUserRequest userRequest) {
    final AsyncJobResult<CloudContextHolder> jobResult =
        jobService.retrieveAsyncJobResult(jobId, CloudContextHolder.class, userRequest);

    ApiGcpContext gcpContext = null;
    ApiAzureContext azureContext = null;

    if (jobResult.getJobReport().getStatus().equals(StatusEnum.SUCCEEDED)) {
      gcpContext =
          Optional.ofNullable(jobResult.getResult().getGcpCloudContext())
              .map(c -> new ApiGcpContext().projectId(c.getGcpProjectId()))
              .orElse(null);

      azureContext =
          Optional.ofNullable(jobResult.getResult().getAzureCloudContext())
              .map(
                  c ->
                      new ApiAzureContext()
                          .tenantId(c.getAzureTenantId())
                          .subscriptionId(c.getAzureSubscriptionId())
                          .resourceGroupId(c.getAzureResourceGroupId()))
              .orElse(null);
    }

    return new ApiCreateCloudContextResult()
        .jobReport(jobResult.getJobReport())
        .errorReport(jobResult.getApiErrorReport())
        .gcpContext(gcpContext)
        .azureContext(azureContext);
  }

  @Override
  public ResponseEntity<Void> deleteCloudContext(UUID uuid, ApiCloudPlatform cloudPlatform) {
    AuthenticatedUserRequest userRequest = getAuthenticatedInfo();
    ControllerValidationUtils.validateCloudPlatform(cloudPlatform);
    if (cloudPlatform == ApiCloudPlatform.AZURE) {
      workspaceService.deleteAzureCloudContext(uuid, userRequest);
    } else {
      workspaceService.deleteGcpCloudContext(uuid, userRequest);
    }
    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  @Override
  public ResponseEntity<Void> enablePet(UUID workspaceUuid) {
    AuthenticatedUserRequest userRequest = getAuthenticatedInfo();
    // TODO(PF-1007): This would be a nice use for an authorized workspace ID.
    // Validate that the user is a workspace member, as enablePetServiceAccountImpersonation does
    // not authenticate.
    workspaceService.validateWorkspaceAndAction(
        userRequest, workspaceUuid, SamConstants.SamWorkspaceAction.READ);
    String userEmail =
        SamRethrow.onInterrupted(() -> samService.getUserEmailFromSam(userRequest), "enablePet");
    petSaService.enablePetServiceAccountImpersonation(workspaceUuid, userEmail, userRequest);
    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  /**
   * Clone an entire workspace by creating a new workspace and cloning the workspace's resources
   * into it.
   *
   * @param workspaceUuid - ID of source workspace
   * @param body - request body
   * @return - result structure for the overall clone operation with details for each resource
   */
  @Override
  public ResponseEntity<ApiCloneWorkspaceResult> cloneWorkspace(
      UUID workspaceUuid, @Valid ApiCloneWorkspaceRequest body) {
    final AuthenticatedUserRequest petRequest = getCloningCredentials(workspaceUuid);

    Optional<SpendProfileId> spendProfileId =
        Optional.ofNullable(body.getSpendProfile()).map(SpendProfileId::new);
    final UUID destinationWorkspaceId = UUID.randomUUID();

    // ET uses userFacingId; CWB doesn't. Schema enforces that userFacingId must be set. CWB doesn't
    // pass userFacingId in request, so use id. Prefix with "a" because userFacingId must start with
    // letter.
    String destinationUserFacingId =
        Optional.ofNullable(body.getUserFacingId()).orElse("a-" + destinationWorkspaceId);
    ControllerValidationUtils.validateUserFacingId(destinationUserFacingId);

    // Construct the target workspace object from the inputs
    final Workspace destinationWorkspace =
        Workspace.builder()
            .workspaceId(destinationWorkspaceId)
            .userFacingId(destinationUserFacingId)
            .spendProfileId(spendProfileId.orElse(null))
            .workspaceStage(WorkspaceStage.MC_WORKSPACE)
            .displayName(body.getDisplayName())
            .description(body.getDescription())
            .properties(propertyMapFromApi(body.getProperties()))
            .build();

    final String jobId =
        workspaceService.cloneWorkspace(
            workspaceUuid, petRequest, body.getLocation(), destinationWorkspace);

    final ApiCloneWorkspaceResult result = fetchCloneWorkspaceResult(jobId, getAuthenticatedInfo());
    final ApiClonedWorkspace clonedWorkspaceStub =
        new ApiClonedWorkspace()
            .destinationWorkspaceId(destinationWorkspaceId)
            .destinationUserFacingId(destinationUserFacingId)
            .sourceWorkspaceId(workspaceUuid);
    result.setWorkspace(clonedWorkspaceStub);
    return new ResponseEntity<>(result, getAsyncResponseCode(result.getJobReport()));
  }

  /**
   * Return the workspace clone result, including job result and error result.
   *
   * @param workspaceUuid - source workspace ID
   * @param jobId - ID of flight
   * @return - response with result
   */
  @Override
  public ResponseEntity<ApiCloneWorkspaceResult> getCloneWorkspaceResult(
      UUID workspaceUuid, String jobId) {
    final AuthenticatedUserRequest userRequest = getAuthenticatedInfo();
    final ApiCloneWorkspaceResult result = fetchCloneWorkspaceResult(jobId, userRequest);
    return new ResponseEntity<>(result, getAsyncResponseCode(result.getJobReport()));
  }

  // Retrieve the async result or progress for clone workspace.
  private ApiCloneWorkspaceResult fetchCloneWorkspaceResult(
      String jobId, AuthenticatedUserRequest userRequest) {
    final AsyncJobResult<ApiClonedWorkspace> jobResult =
        jobService.retrieveAsyncJobResult(jobId, ApiClonedWorkspace.class, userRequest);
    return new ApiCloneWorkspaceResult()
        .jobReport(jobResult.getJobReport())
        .errorReport(jobResult.getApiErrorReport())
        .workspace(jobResult.getResult());
  }

  // Convert properties list into a map
  private Map<String, String> propertyMapFromApi(ApiProperties properties) {
    Map<String, String> propertyMap = new HashMap<>();
    if (properties != null) {
      for (ApiProperty property : properties) {
        ControllerValidationUtils.validatePropertyKey(property.getKey());
        propertyMap.put(property.getKey(), property.getValue());
      }
    }
    return propertyMap;
  }

  /**
   * Return Pet SA credentials if available, otherwise the user credentials associated with this
   * request. It's possible to clone a workspace that has no cloud context, and thus no (GCP) pet
   * account.
   *
   * @param workspaceUuid - ID of workspace to be cloned
   * @return user or pet request
   */
  private AuthenticatedUserRequest getCloningCredentials(UUID workspaceUuid) {
    final AuthenticatedUserRequest userRequest = getAuthenticatedInfo();
    return petSaService.getWorkspacePetCredentials(workspaceUuid, userRequest).orElse(userRequest);
  }
}
