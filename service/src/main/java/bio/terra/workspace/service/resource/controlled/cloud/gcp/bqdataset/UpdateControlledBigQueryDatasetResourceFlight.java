package bio.terra.workspace.service.resource.controlled.cloud.gcp.bqdataset;

import bio.terra.stairway.Flight;
import bio.terra.stairway.FlightMap;
import bio.terra.stairway.RetryRule;
import bio.terra.workspace.common.utils.FlightBeanBag;
import bio.terra.workspace.common.utils.RetryRules;
import bio.terra.workspace.service.resource.controlled.flight.update.RetrieveControlledResourceMetadataStep;
import bio.terra.workspace.service.resource.controlled.flight.update.UpdateControlledResourceMetadataStep;
import bio.terra.workspace.service.resource.controlled.model.ControlledResource;
import bio.terra.workspace.service.resource.model.WsmResourceType;
import bio.terra.workspace.service.workspace.flight.WorkspaceFlightMapKeys.ResourceKeys;

public class UpdateControlledBigQueryDatasetResourceFlight extends Flight {

  private final RetryRule gcpRetryRule = RetryRules.cloud();

  public UpdateControlledBigQueryDatasetResourceFlight(FlightMap inputParameters, Object beanBag) {
    super(inputParameters, beanBag);

    final FlightBeanBag flightBeanBag = FlightBeanBag.getFromObject(beanBag);
    final ControlledResource resource =
        inputParameters.get(ResourceKeys.RESOURCE, ControlledResource.class);

    // get copy of existing metadata
    addStep(
        new RetrieveControlledResourceMetadataStep(
            flightBeanBag.getResourceDao(), resource.getWorkspaceId(), resource.getResourceId()));

    // update the metadata (name & description of resource)
    addStep(
        new UpdateControlledResourceMetadataStep(
            flightBeanBag.getControlledResourceMetadataManager(),
            flightBeanBag.getResourceDao(),
            resource));

    // retrieve existing attributes in case of undo later
    addStep(
        new RetrieveBigQueryDatasetCloudAttributesStep(
            resource.castByEnum(WsmResourceType.CONTROLLED_GCP_BIG_QUERY_DATASET),
            flightBeanBag.getCrlService(),
            flightBeanBag.getGcpCloudContextService()),
        gcpRetryRule);

    // Update the dataset's cloud attributes
    addStep(
        new UpdateBigQueryDatasetStep(
            resource.castByEnum(WsmResourceType.CONTROLLED_GCP_BIG_QUERY_DATASET),
            flightBeanBag.getCrlService(),
            flightBeanBag.getGcpCloudContextService()),
        gcpRetryRule);
  }
}
