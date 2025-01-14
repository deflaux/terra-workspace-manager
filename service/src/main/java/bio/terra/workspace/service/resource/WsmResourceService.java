package bio.terra.workspace.service.resource;

import bio.terra.workspace.db.ResourceDao;
import bio.terra.workspace.service.iam.AuthenticatedUserRequest;
import bio.terra.workspace.service.iam.model.SamConstants;
import bio.terra.workspace.service.resource.model.StewardshipType;
import bio.terra.workspace.service.resource.model.WsmResource;
import bio.terra.workspace.service.resource.model.WsmResourceFamily;
import bio.terra.workspace.service.workspace.WorkspaceService;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Support for cross-resource methods */
@Component
public class WsmResourceService {

  private final WorkspaceService workspaceService;
  private final ResourceDao resourceDao;

  @Autowired
  public WsmResourceService(WorkspaceService workspaceService, ResourceDao resourceDao) {
    this.workspaceService = workspaceService;
    this.resourceDao = resourceDao;
  }

  public List<WsmResource> enumerateResources(
      UUID workspaceUuid,
      @Nullable WsmResourceFamily cloudResourceType,
      @Nullable StewardshipType stewardshipType,
      int offset,
      int limit,
      AuthenticatedUserRequest userRequest) {

    // First, we check if the caller has read action on the workspace. If not, we are done. They see
    // nothing!
    workspaceService.validateWorkspaceAndAction(
        userRequest, workspaceUuid, SamConstants.SamWorkspaceAction.READ);

    return resourceDao.enumerateResources(
        workspaceUuid, cloudResourceType, stewardshipType, offset, limit);
  }
}
