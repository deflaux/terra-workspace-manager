package bio.terra.workspace.service.resource.controlled.flight.create.notebook;

import static bio.terra.workspace.service.workspace.flight.WorkspaceFlightMapKeys.ControlledResourceKeys.CREATE_NOTEBOOK_PARAMETERS;
import static bio.terra.workspace.service.workspace.flight.WorkspaceFlightMapKeys.ControlledResourceKeys.CREATE_NOTEBOOK_SERVICE_ACCOUNT_ID;

import bio.terra.cloudres.google.api.services.common.OperationCow;
import bio.terra.cloudres.google.api.services.common.OperationUtils;
import bio.terra.cloudres.google.notebooks.AIPlatformNotebooksCow;
import bio.terra.cloudres.google.notebooks.InstanceName;
import bio.terra.stairway.FlightContext;
import bio.terra.stairway.Step;
import bio.terra.stairway.StepResult;
import bio.terra.stairway.StepStatus;
import bio.terra.stairway.exception.RetryException;
import bio.terra.workspace.generated.model.ApiGcpAiNotebookInstanceContainerImage;
import bio.terra.workspace.generated.model.ApiGcpAiNotebookInstanceCreationParameters;
import bio.terra.workspace.generated.model.ApiGcpAiNotebookInstanceVmImage;
import bio.terra.workspace.service.crl.CrlService;
import bio.terra.workspace.service.resource.controlled.ControlledAiNotebookInstanceResource;
import bio.terra.workspace.service.workspace.WorkspaceService;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.notebooks.v1.model.ContainerImage;
import com.google.api.services.notebooks.v1.model.Instance;
import com.google.api.services.notebooks.v1.model.Operation;
import com.google.api.services.notebooks.v1.model.VmImage;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * A step for creating the AI Platform notebook instance in the Google cloud.
 *
 * <p>Undo deletes the created notebook instance.
 */
public class CreateAiNotebookInstanceStep implements Step {
  private final Logger logger = LoggerFactory.getLogger(CreateAiNotebookInstanceStep.class);
  private final CrlService crlService;
  private final ControlledAiNotebookInstanceResource resource;
  private final WorkspaceService workspaceService;

  public CreateAiNotebookInstanceStep(
      CrlService crlService,
      ControlledAiNotebookInstanceResource resource,
      WorkspaceService workspaceService) {
    this.crlService = crlService;
    this.resource = resource;
    this.workspaceService = workspaceService;
  }

  @Override
  public StepResult doStep(FlightContext flightContext)
      throws InterruptedException, RetryException {
    String projectId = workspaceService.getRequiredGcpProject(resource.getWorkspaceId());
    InstanceName instanceName = createInstanceName(projectId);
    Instance instance = createInstanceModel(flightContext, projectId);

    AIPlatformNotebooksCow notebooks = crlService.getAIPlatformNotebooksCow();
    try {
      OperationCow<Operation> creationOperation;
      try {
        creationOperation =
            notebooks
                .operations()
                .operationCow(notebooks.instances().create(instanceName, instance).execute());
      } catch (GoogleJsonResponseException e) {
        // If the instance already exists, this step must have already run successfully. Otherwise
        // retry.
        if (HttpStatus.CONFLICT.value() == e.getStatusCode()) {
          logger.debug("Notebook instance {} already created.", instanceName.formatName());
          return StepResult.getStepResultSuccess();
        }
        return new StepResult(StepStatus.STEP_RESULT_FAILURE_RETRY, e);
      }

      creationOperation =
          OperationUtils.pollUntilComplete(
              creationOperation, Duration.ofSeconds(20), Duration.ofMinutes(12));
      if (creationOperation.getOperation().getError() != null) {
        throw new RetryException(
            String.format(
                "Error creating notebook instance {}. {}",
                instanceName.formatName(),
                creationOperation.getOperation().getError()));
      }
    } catch (IOException e) {
      return new StepResult(StepStatus.STEP_RESULT_FAILURE_RETRY, e);
    }
    return StepResult.getStepResultSuccess();
  }

  private static Instance createInstanceModel(FlightContext flightContext, String projectId) {
    Instance instance = new Instance();
    ApiGcpAiNotebookInstanceCreationParameters creationParameters =
        flightContext
            .getInputParameters()
            .get(CREATE_NOTEBOOK_PARAMETERS, ApiGcpAiNotebookInstanceCreationParameters.class);
    setFields(creationParameters, instance);

    String serviceAccountEmail =
        CreateServiceAccountStep.serviceAccountEmail(
            flightContext.getWorkingMap().get(CREATE_NOTEBOOK_SERVICE_ACCOUNT_ID, String.class),
            projectId);
    instance.setServiceAccount(serviceAccountEmail);

    // Create the AI Notebook instance in the service account proxy mode to control proxy access by
    // means of IAM permissions on the service account.
    // https://cloud.google.com/ai-platform/notebooks/docs/troubleshooting#opening_a_notebook_results_in_a_403_forbidden_error
    ImmutableMap<String, String> metadata =
        new ImmutableMap.Builder<String, String>().put("proxy-mode", "service_account").build();
    instance.setMetadata(metadata);

    setNetworks(instance, projectId, creationParameters.getLocation());

    return instance;
  }

  private static void setFields(
      ApiGcpAiNotebookInstanceCreationParameters creationParameters, Instance instance) {
    instance
        .setPostStartupScript(creationParameters.getPostStartupScript())
        .setMachineType(creationParameters.getMachineType());
    ApiGcpAiNotebookInstanceVmImage vmImageParameters = creationParameters.getVmImage();
    if (vmImageParameters != null) {
      instance.setVmImage(
          new VmImage()
              .setProject(vmImageParameters.getProjectId())
              .setImageFamily(vmImageParameters.getImageFamily())
              .setImageName(vmImageParameters.getImageName()));
    }
    ApiGcpAiNotebookInstanceContainerImage containerImageParameters =
        creationParameters.getContainerImage();
    if (containerImageParameters != null) {
      instance.setContainerImage(
          new ContainerImage()
              .setRepository(containerImageParameters.getRepository())
              .setTag(containerImageParameters.getTag()));
    }
  }

  private static void setNetworks(Instance instance, String projectId, String location) {
    // 'network' is the name of the VPC network instance created by the Buffer Service.
    // TODO(PPF-469): Instead of hard coding this, look up the name of the network on the project.
    instance.setNetwork("projects/" + projectId + "/global/networks/network");
    // Assume the region is related to the location like 'us-west1' is to 'us-west1-b'.
    Preconditions.checkArgument(location.length() > 2, "Invalid location '%s'", location);
    String region = location.substring(0, location.length() - 2);
    // Like 'network', 'subnetwork' is the name of the subnetwork created by the Buffer Service in
    // each region.
    instance.setSubnet("projects/" + projectId + "/regions/" + region + "/subnetworks/subnetwork");
  }

  private InstanceName createInstanceName(String projectId) {
    return InstanceName.builder()
        .projectId(projectId)
        .location(resource.getLocation())
        .instanceId(resource.getInstanceId())
        .build();
  }

  @Override
  public StepResult undoStep(FlightContext flightContext) throws InterruptedException {
    String projectId = workspaceService.getRequiredGcpProject(resource.getWorkspaceId());
    InstanceName instanceName = createInstanceName(projectId);

    AIPlatformNotebooksCow notebooks = crlService.getAIPlatformNotebooksCow();
    try {
      OperationCow<Operation> deletionOperation;
      try {
        deletionOperation =
            notebooks
                .operations()
                .operationCow(notebooks.instances().delete(instanceName).execute());
      } catch (GoogleJsonResponseException e) {
        // The AI notebook instance may never have been created or have already been deleted.
        if (e.getStatusCode() == HttpStatus.NOT_FOUND.value()) {
          logger.debug("No notebook instance {} to delete.", instanceName.formatName());
          return StepResult.getStepResultSuccess();
        }
        return new StepResult(StepStatus.STEP_RESULT_FAILURE_RETRY, e);
      }
      deletionOperation =
          OperationUtils.pollUntilComplete(
              deletionOperation, Duration.ofSeconds(20), Duration.ofMinutes(12));
      if (deletionOperation.getOperation().getError() != null) {
        logger.debug(
            "Error deleting notebook instance {}. {}",
            instanceName.formatName(),
            deletionOperation.getOperation().getError());
        return new StepResult(StepStatus.STEP_RESULT_FAILURE_RETRY);
      }
    } catch (IOException e) {
      return new StepResult(StepStatus.STEP_RESULT_FAILURE_RETRY, e);
    }
    return StepResult.getStepResultSuccess();
  }
}