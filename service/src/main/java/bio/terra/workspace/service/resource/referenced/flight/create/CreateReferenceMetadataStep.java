package bio.terra.workspace.service.resource.referenced.flight.create;

import bio.terra.stairway.FlightContext;
import bio.terra.stairway.FlightMap;
import bio.terra.stairway.Step;
import bio.terra.stairway.StepResult;
import bio.terra.stairway.exception.RetryException;
import bio.terra.workspace.common.utils.FlightUtils;
import bio.terra.workspace.db.ResourceDao;
import bio.terra.workspace.service.resource.model.WsmResource;
import bio.terra.workspace.service.resource.model.WsmResourceType;
import bio.terra.workspace.service.workspace.flight.WorkspaceFlightMapKeys.ResourceKeys;
import org.springframework.http.HttpStatus;

/** Stairway step to persist a data reference in WSM's database. */
public class CreateReferenceMetadataStep implements Step {

  private final ResourceDao resourceDao;

  public CreateReferenceMetadataStep(ResourceDao resourceDao) {
    this.resourceDao = resourceDao;
  }

  @Override
  public StepResult doStep(FlightContext flightContext)
      throws RetryException, InterruptedException {
    WsmResource referencedResource = getReferencedResource(flightContext);
    resourceDao.createReferencedResource(referencedResource);
    FlightUtils.setResponse(flightContext, referencedResource.getResourceId(), HttpStatus.OK);
    return StepResult.getStepResultSuccess();
  }

  @Override
  public StepResult undoStep(FlightContext flightContext) throws InterruptedException {
    WsmResource referencedResource = getReferencedResource(flightContext);
    // Ignore return value, as we don't care whether a reference was deleted or just not found.
    resourceDao.deleteResource(
        referencedResource.getWorkspaceId(), referencedResource.getResourceId());

    return StepResult.getStepResultSuccess();
  }

  private WsmResource getReferencedResource(FlightContext flightContext) {
    FlightMap inputMap = flightContext.getInputParameters();
    WsmResourceType resourceType =
        WsmResourceType.valueOf(inputMap.get(ResourceKeys.RESOURCE_TYPE, String.class));
    // Use the resource type to deserialize the right class
    return inputMap.get(ResourceKeys.RESOURCE, resourceType.getResourceClass());
  }
}
