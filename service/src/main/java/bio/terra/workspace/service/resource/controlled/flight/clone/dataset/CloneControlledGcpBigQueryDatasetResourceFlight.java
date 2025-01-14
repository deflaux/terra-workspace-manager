package bio.terra.workspace.service.resource.controlled.flight.clone.dataset;

import bio.terra.stairway.Flight;
import bio.terra.stairway.FlightMap;
import bio.terra.workspace.common.utils.FlightBeanBag;
import bio.terra.workspace.common.utils.FlightUtils;
import bio.terra.workspace.common.utils.RetryRules;
import bio.terra.workspace.service.iam.AuthenticatedUserRequest;
import bio.terra.workspace.service.job.JobMapKeys;
import bio.terra.workspace.service.resource.controlled.cloud.gcp.bqdataset.ControlledBigQueryDatasetResource;
import bio.terra.workspace.service.resource.controlled.flight.clone.CheckControlledResourceAuthStep;
import bio.terra.workspace.service.resource.controlled.flight.update.RetrieveControlledResourceMetadataStep;
import bio.terra.workspace.service.resource.controlled.model.ControlledResource;
import bio.terra.workspace.service.resource.model.CloningInstructions;
import bio.terra.workspace.service.resource.model.WsmResourceType;
import bio.terra.workspace.service.workspace.flight.WorkspaceFlightMapKeys.ControlledResourceKeys;
import bio.terra.workspace.service.workspace.flight.WorkspaceFlightMapKeys.ResourceKeys;
import java.util.Optional;

public class CloneControlledGcpBigQueryDatasetResourceFlight extends Flight {

  public CloneControlledGcpBigQueryDatasetResourceFlight(
      FlightMap inputParameters, Object applicationContext) {
    super(inputParameters, applicationContext);

    FlightUtils.validateRequiredEntries(
        inputParameters,
        ResourceKeys.RESOURCE,
        JobMapKeys.AUTH_USER_INFO.getKeyName(),
        ControlledResourceKeys.CLONING_INSTRUCTIONS,
        ControlledResourceKeys.DESTINATION_RESOURCE_ID);

    final FlightBeanBag flightBeanBag = FlightBeanBag.getFromObject(applicationContext);
    final ControlledResource sourceResource =
        inputParameters.get(ResourceKeys.RESOURCE, ControlledResource.class);
    final AuthenticatedUserRequest userRequest =
        inputParameters.get(JobMapKeys.AUTH_USER_INFO.getKeyName(), AuthenticatedUserRequest.class);

    final CloningInstructions resolvedCloningInstructions =
        Optional.ofNullable(
                inputParameters.get(
                    ControlledResourceKeys.CLONING_INSTRUCTIONS, CloningInstructions.class))
            .orElse(sourceResource.getCloningInstructions());

    if (CloningInstructions.COPY_NOTHING == resolvedCloningInstructions) {
      addStep(
          new SetNoOpBucketCloneResponseStep(
              sourceResource.castByEnum(WsmResourceType.CONTROLLED_GCP_BIG_QUERY_DATASET)));
    } else if (CloningInstructions.COPY_RESOURCE == resolvedCloningInstructions
        || CloningInstructions.COPY_DEFINITION == resolvedCloningInstructions) {
      // Flight Plan
      // 1. Validate user has read access to the source object
      // 2. Gather controlled resource metadata for source object
      // 3. Gather creation parameters from existing object
      // 4. Launch sub-flight to create appropriate resource
      addStep(
          new CheckControlledResourceAuthStep(
              sourceResource, flightBeanBag.getControlledResourceMetadataManager(), userRequest),
          RetryRules.shortExponential());
      addStep(
          new RetrieveControlledResourceMetadataStep(
              flightBeanBag.getResourceDao(),
              sourceResource.getWorkspaceId(),
              sourceResource.getResourceId()));

      final ControlledBigQueryDatasetResource sourceDataset =
          sourceResource.castByEnum(WsmResourceType.CONTROLLED_GCP_BIG_QUERY_DATASET);

      addStep(
          new RetrieveBigQueryDatasetCloudAttributesStep(
              sourceDataset,
              flightBeanBag.getCrlService(),
              flightBeanBag.getGcpCloudContextService()),
          RetryRules.cloud());

      addStep(
          new CopyBigQueryDatasetDefinitionStep(
              sourceDataset,
              flightBeanBag.getControlledResourceService(),
              userRequest,
              flightBeanBag.getGcpCloudContextService(),
              resolvedCloningInstructions));
      if (CloningInstructions.COPY_RESOURCE == resolvedCloningInstructions) {
        addStep(
            new CreateTableCopyJobsStep(
                flightBeanBag.getCrlService(),
                flightBeanBag.getGcpCloudContextService(),
                sourceDataset),
            RetryRules.cloud());
        addStep(
            new CompleteTableCopyJobsStep(flightBeanBag.getCrlService()),
            RetryRules.cloudLongRunning());
      }
    } else {
      throw new IllegalArgumentException(
          String.format(
              "Cloining Instructions %s not supported", resolvedCloningInstructions.toString()));
    }
  }
}
