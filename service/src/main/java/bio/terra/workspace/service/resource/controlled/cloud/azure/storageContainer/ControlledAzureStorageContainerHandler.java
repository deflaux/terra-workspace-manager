package bio.terra.workspace.service.resource.controlled.cloud.azure.storageContainer;

import bio.terra.workspace.db.DbSerDes;
import bio.terra.workspace.db.model.DbResource;
import bio.terra.workspace.service.resource.controlled.model.ControlledResourceFields;
import bio.terra.workspace.service.resource.model.WsmResource;
import bio.terra.workspace.service.resource.model.WsmResourceHandler;

public class ControlledAzureStorageContainerHandler implements WsmResourceHandler {
  private static ControlledAzureStorageContainerHandler theHandler;

  public static ControlledAzureStorageContainerHandler getHandler() {
    if (theHandler == null) {
      theHandler = new ControlledAzureStorageContainerHandler();
    }
    return theHandler;
  }

  /** {@inheritDoc} */
  @Override
  public WsmResource makeResourceFromDb(DbResource dbResource) {
    ControlledAzureStorageContainerAttributes attributes =
        DbSerDes.fromJson(
            dbResource.getAttributes(), ControlledAzureStorageContainerAttributes.class);

    return ControlledAzureStorageContainerResource.builder()
        .storageAccountId(attributes.getStorageAccountId())
        .storageContainerName(attributes.getStorageContainerName())
        .common(new ControlledResourceFields(dbResource))
        .build();
  }
}
