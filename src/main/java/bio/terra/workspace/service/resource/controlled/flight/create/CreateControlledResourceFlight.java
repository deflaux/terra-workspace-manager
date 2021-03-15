package bio.terra.workspace.service.resource.controlled.flight.create;

import bio.terra.stairway.Flight;
import bio.terra.stairway.FlightMap;
import bio.terra.workspace.common.utils.FlightBeanBag;
import bio.terra.workspace.service.iam.AuthenticatedUserRequest;
import bio.terra.workspace.service.job.JobMapKeys;
import bio.terra.workspace.service.resource.controlled.ControlledResource;
import bio.terra.workspace.service.workspace.flight.CreateSamResourceStep;

/**
 * Flight for creation of a controlled resource. Some steps are resource-type-agnostic, and others
 * depend on the resource type. The latter must be passed in via the input parameters map with keys
 */
public class CreateControlledResourceFlight extends Flight {

  public CreateControlledResourceFlight(FlightMap inputParameters, Object beanBag) {
    super(inputParameters, beanBag);
    final FlightBeanBag flightBeanBag = FlightBeanBag.getFromObject(beanBag);

    // store the resource metadata in the WSM database
    addStep(new StoreMetadataStep(flightBeanBag.getResourceDao()));

    // create the cloud resource via CRL
    final ControlledResource resource =
        inputParameters.get(JobMapKeys.REQUEST.getKeyName(), ControlledResource.class);
    final AuthenticatedUserRequest userRequest =
        inputParameters.get(JobMapKeys.AUTH_USER_INFO.getKeyName(), AuthenticatedUserRequest.class);

    switch (resource.getResourceType()) {
      case GCS_BUCKET:
        addStep(
            new CreateGcsBucketStep(
                flightBeanBag.getCrlService(), resource.castToGcsBucketResource(), userRequest));
        break;
      case BIG_QUERY_DATASET:
      default:
        throw new IllegalStateException(
            String.format("Unrecognized resource type %s", resource.getResourceType()));
    }

    // create the Sam resource associated with the resource
    addStep(new CreateSamResourceStep(flightBeanBag.getSamService()));

    // assign custom roles to the resource based on Sam policies
    // TODO: can this step be the same for all resource types?
  }
}