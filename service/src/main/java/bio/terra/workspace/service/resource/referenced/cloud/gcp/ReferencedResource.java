package bio.terra.workspace.service.resource.referenced.cloud.gcp;

import bio.terra.workspace.common.utils.FlightBeanBag;
import bio.terra.workspace.db.exception.InvalidMetadataException;
import bio.terra.workspace.db.model.DbResource;
import bio.terra.workspace.service.iam.AuthenticatedUserRequest;
import bio.terra.workspace.service.resource.model.CloningInstructions;
import bio.terra.workspace.service.resource.model.StewardshipType;
import bio.terra.workspace.service.resource.model.WsmResource;
import java.util.UUID;
import javax.annotation.Nullable;

public abstract class ReferencedResource extends WsmResource {
  public ReferencedResource(
      UUID workspaceUuid,
      UUID resourceId,
      String name,
      @Nullable String description,
      CloningInstructions cloningInstructions) {
    super(workspaceUuid, resourceId, name, description, cloningInstructions);
  }

  public ReferencedResource(DbResource dbResource) {
    super(dbResource);
    if (dbResource.getStewardshipType() != StewardshipType.REFERENCED) {
      throw new InvalidMetadataException("Expected REFERENCE");
    }
  }

  @Override
  public StewardshipType getStewardshipType() {
    return StewardshipType.REFERENCED;
  }

  /**
   * Check for a user's access to the resource being referenced. This call should talk to an
   * external service (a cloud platform, Terra Data Repo, etc) specific to the referenced resource
   * type. This call will impersonate a user via the provided credentials.
   *
   * @param context A FlightBeanBag holding Service objects for talking to various external services
   * @param userRequest Credentials of the user to impersonate for validation
   */
  public abstract boolean checkAccess(FlightBeanBag context, AuthenticatedUserRequest userRequest);
}
