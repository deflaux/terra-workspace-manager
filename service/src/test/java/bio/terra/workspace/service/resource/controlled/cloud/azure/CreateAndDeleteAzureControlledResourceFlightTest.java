package bio.terra.workspace.service.resource.controlled.cloud.azure;

import static org.junit.jupiter.api.Assertions.*;

import bio.terra.stairway.FlightState;
import bio.terra.stairway.FlightStatus;
import bio.terra.workspace.common.BaseAzureTest;
import bio.terra.workspace.common.StairwayTestUtils;
import bio.terra.workspace.common.fixtures.ControlledResourceFixtures;
import bio.terra.workspace.common.utils.AzureTestUtils;
import bio.terra.workspace.common.utils.AzureVmUtils;
import bio.terra.workspace.connected.UserAccessUtils;
import bio.terra.workspace.generated.model.*;
import bio.terra.workspace.service.iam.AuthenticatedUserRequest;
import bio.terra.workspace.service.job.JobService;
import bio.terra.workspace.service.resource.WsmResourceService;
import bio.terra.workspace.service.resource.controlled.ControlledResourceService;
import bio.terra.workspace.service.resource.controlled.cloud.azure.disk.ControlledAzureDiskResource;
import bio.terra.workspace.service.resource.controlled.cloud.azure.ip.ControlledAzureIpResource;
import bio.terra.workspace.service.resource.controlled.cloud.azure.network.ControlledAzureNetworkResource;
import bio.terra.workspace.service.resource.controlled.cloud.azure.relayNamespace.ControlledAzureRelayNamespaceResource;
import bio.terra.workspace.service.resource.controlled.cloud.azure.storage.ControlledAzureStorageResource;
import bio.terra.workspace.service.resource.controlled.cloud.azure.storageContainer.ControlledAzureStorageContainerResource;
import bio.terra.workspace.service.resource.controlled.cloud.azure.vm.AzureVmHelper;
import bio.terra.workspace.service.resource.controlled.cloud.azure.vm.ControlledAzureVmResource;
import bio.terra.workspace.service.resource.controlled.flight.create.CreateControlledResourceFlight;
import bio.terra.workspace.service.resource.controlled.flight.delete.DeleteControlledResourceFlight;
import bio.terra.workspace.service.resource.controlled.model.AccessScopeType;
import bio.terra.workspace.service.resource.controlled.model.ControlledResource;
import bio.terra.workspace.service.resource.controlled.model.ControlledResourceFields;
import bio.terra.workspace.service.resource.controlled.model.ManagedByType;
import bio.terra.workspace.service.resource.model.CloningInstructions;
import bio.terra.workspace.service.resource.model.WsmResource;
import bio.terra.workspace.service.resource.model.WsmResourceType;
import bio.terra.workspace.service.workspace.WorkspaceService;
import bio.terra.workspace.service.workspace.flight.create.azure.CreateAzureContextFlight;
import bio.terra.workspace.service.workspace.model.AzureCloudContext;
import com.azure.core.management.exception.ManagementException;
import com.azure.resourcemanager.compute.ComputeManager;
import com.azure.resourcemanager.compute.models.VirtualMachine;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

public class CreateAndDeleteAzureControlledResourceFlightTest extends BaseAzureTest {
  private static final Duration STAIRWAY_FLIGHT_TIMEOUT = Duration.ofMinutes(15);

  @Autowired private WorkspaceService workspaceService;
  @Autowired private JobService jobService;
  @Autowired private AzureTestUtils azureTestUtils;
  @Autowired private UserAccessUtils userAccessUtils;
  @Autowired private ControlledResourceService controlledResourceService;
  @Autowired private WsmResourceService wsmResourceService;

  private void createCloudContext(UUID workspaceUuid, AuthenticatedUserRequest userRequest)
      throws InterruptedException {
    FlightState createAzureContextFlightState =
        StairwayTestUtils.blockUntilFlightCompletes(
            jobService.getStairway(),
            CreateAzureContextFlight.class,
            azureTestUtils.createAzureContextInputParameters(workspaceUuid, userRequest),
            STAIRWAY_FLIGHT_TIMEOUT,
            null);

    assertEquals(FlightStatus.SUCCESS, createAzureContextFlightState.getFlightStatus());
    assertTrue(
        workspaceService.getAuthorizedAzureCloudContext(workspaceUuid, userRequest).isPresent());
  }

  private void createVMResource(
      UUID workspaceUuid,
      AuthenticatedUserRequest userRequest,
      ControlledResource resource,
      ApiAzureVmCreationParameters vmCreationParameters)
      throws InterruptedException {
    createResource(
        workspaceUuid,
        userRequest,
        resource,
        WsmResourceType.CONTROLLED_AZURE_VM,
        vmCreationParameters);
  }

  private void createResource(
      UUID workspaceUuid,
      AuthenticatedUserRequest userRequest,
      ControlledResource resource,
      WsmResourceType resourceType)
      throws InterruptedException {
    createResource(workspaceUuid, userRequest, resource, resourceType, null);
  }

  private void createResource(
      UUID workspaceUuid,
      AuthenticatedUserRequest userRequest,
      ControlledResource resource,
      WsmResourceType resourceType,
      ApiAzureVmCreationParameters vmCreationParameters)
      throws InterruptedException {

    FlightState flightState =
        StairwayTestUtils.blockUntilFlightCompletes(
            jobService.getStairway(),
            CreateControlledResourceFlight.class,
            azureTestUtils.createControlledResourceInputParameters(
                workspaceUuid, userRequest, resource, vmCreationParameters),
            STAIRWAY_FLIGHT_TIMEOUT,
            null);

    assertEquals(FlightStatus.SUCCESS, flightState.getFlightStatus());

    // Verify controlled resource exists in the workspace.
    ControlledResource res =
        controlledResourceService.getControlledResource(
            workspaceUuid, resource.getResourceId(), userRequest);

    try {
      var castResource = res.castByEnum(resourceType);
      assertEquals(resource, castResource);
    } catch (Exception e) {
      fail(String.format("Failed to cast resource to %s", resourceType), e);
    }
  }

  @Test
  public void createAzureIpControlledResource() throws InterruptedException {
    UUID workspaceUuid = azureTestUtils.createWorkspace(workspaceService);
    AuthenticatedUserRequest userRequest = userAccessUtils.defaultUserAuthRequest();
    createCloudContext(workspaceUuid, userRequest);

    final ApiAzureIpCreationParameters creationParameters =
        ControlledResourceFixtures.getAzureIpCreationParameters();

    // TODO: make this application-private resource once the POC supports it
    final UUID resourceId = UUID.randomUUID();
    ControlledAzureIpResource resource =
        ControlledAzureIpResource.builder()
            .common(
                ControlledResourceFields.builder()
                    .workspaceUuid(workspaceUuid)
                    .resourceId(resourceId)
                    .name(getAzureName("ip"))
                    .description(getAzureName("ip-desc"))
                    .cloningInstructions(CloningInstructions.COPY_RESOURCE)
                    .accessScope(AccessScopeType.fromApi(ApiAccessScope.SHARED_ACCESS))
                    .managedBy(ManagedByType.fromApi(ApiManagedBy.USER))
                    .build())
            .ipName(creationParameters.getName())
            .region(creationParameters.getRegion())
            .build();

    // Submit an IP creation flight and verify the instance is created.
    createResource(workspaceUuid, userRequest, resource, WsmResourceType.CONTROLLED_AZURE_IP);

    // clean up resources - delete ip resource
    submitControlledResourceDeletionFlight(
        workspaceUuid,
        userRequest,
        resource,
        azureTestUtils.getAzureCloudContext().getAzureResourceGroupId(),
        resource.getIpName(),
        azureTestUtils.getComputeManager().networkManager().publicIpAddresses()
            ::getByResourceGroup);
  }

  @Test
  public void createAndDeleteAzureRelayNamespaceControlledResource() throws InterruptedException {
    UUID workspaceUuid = azureTestUtils.createWorkspace(workspaceService);
    AuthenticatedUserRequest userRequest = userAccessUtils.defaultUserAuthRequest();
    createCloudContext(workspaceUuid, userRequest);

    final ApiAzureRelayNamespaceCreationParameters creationParameters =
        ControlledResourceFixtures.getAzureRelayNamespaceCreationParameters();

    final UUID resourceId = UUID.randomUUID();
    ControlledAzureRelayNamespaceResource resource =
        ControlledAzureRelayNamespaceResource.builder()
            .common(
                ControlledResourceFields.builder()
                    .workspaceUuid(workspaceUuid)
                    .resourceId(resourceId)
                    .name(getAzureName("relay"))
                    .description(getAzureName("relay-desc"))
                    .cloningInstructions(CloningInstructions.COPY_RESOURCE)
                    .accessScope(AccessScopeType.fromApi(ApiAccessScope.SHARED_ACCESS))
                    .managedBy(ManagedByType.fromApi(ApiManagedBy.USER))
                    .build())
            .namespaceName(creationParameters.getNamespaceName())
            .region(creationParameters.getRegion())
            .build();

    // Submit a relay creation flight and verify the resource exists in the workspace.
    createResource(
        workspaceUuid, userRequest, resource, WsmResourceType.CONTROLLED_AZURE_RELAY_NAMESPACE);

    // clean up resources - delete relay resource
    submitControlledResourceDeletionFlight(
        workspaceUuid,
        userRequest,
        resource,
        azureTestUtils.getAzureCloudContext().getAzureResourceGroupId(),
        resource.getNamespaceName(),
        azureTestUtils.getRelayManager().namespaces()::getByResourceGroup);
  }

  @Test
  public void createAndDeleteAzureStorageResource() throws InterruptedException {
    UUID workspaceUuid = azureTestUtils.createWorkspace(workspaceService);
    AuthenticatedUserRequest userRequest = userAccessUtils.defaultUserAuthRequest();
    createCloudContext(workspaceUuid, userRequest);

    final ApiAzureStorageCreationParameters accountCreationParameters =
        ControlledResourceFixtures.getAzureStorageCreationParameters();

    final UUID accountResourceId = UUID.randomUUID();
    ControlledAzureStorageResource accountResource =
        ControlledAzureStorageResource.builder()
            .common(
                ControlledResourceFields.builder()
                    .workspaceUuid(workspaceUuid)
                    .resourceId(accountResourceId)
                    .name(getAzureName("rs"))
                    .description(getAzureName("rs-desc"))
                    .cloningInstructions(CloningInstructions.COPY_NOTHING)
                    .accessScope(AccessScopeType.fromApi(ApiAccessScope.SHARED_ACCESS))
                    .managedBy(ManagedByType.fromApi(ApiManagedBy.USER))
                    .build())
            .storageAccountName(accountCreationParameters.getStorageAccountName())
            .region(accountCreationParameters.getRegion())
            .build();

    // Submit a storage account creation flight and then verify the resource exists in the
    // workspace.
    createResource(
        workspaceUuid,
        userRequest,
        accountResource,
        WsmResourceType.CONTROLLED_AZURE_STORAGE_ACCOUNT);

    // Submit a storage container creation flight and then verify the resource exists in the
    // workspace.
    final UUID containerResourceId = UUID.randomUUID();
    final String containerName = ControlledResourceFixtures.uniqueBucketName();
    ControlledAzureStorageContainerResource containerResource =
        ControlledAzureStorageContainerResource.builder()
            .common(
                ControlledResourceFields.builder()
                    .workspaceUuid(workspaceUuid)
                    .resourceId(containerResourceId)
                    .name(getAzureName("rc"))
                    .description(getAzureName("rc-desc"))
                    .cloningInstructions(CloningInstructions.COPY_NOTHING)
                    .accessScope(AccessScopeType.fromApi(ApiAccessScope.SHARED_ACCESS))
                    .managedBy(ManagedByType.fromApi(ApiManagedBy.USER))
                    .build())
            .storageAccountId(accountResourceId)
            .storageContainerName(containerName)
            .build();
    createResource(
        workspaceUuid,
        userRequest,
        containerResource,
        WsmResourceType.CONTROLLED_AZURE_STORAGE_CONTAINER);

    // clean up resources - delete storage container resource
    submitControlledResourceDeletionFlight(
        workspaceUuid,
        userRequest,
        containerResource,
        azureTestUtils.getAzureCloudContext().getAzureResourceGroupId(),
        containerResource.getStorageContainerName(),
        null); // Don't sleep/verify deletion yet.

    // clean up resources - delete storage account resource
    submitControlledResourceDeletionFlight(
        workspaceUuid,
        userRequest,
        accountResource,
        azureTestUtils.getAzureCloudContext().getAzureResourceGroupId(),
        accountResource.getStorageAccountName(),
        azureTestUtils.getStorageManager().storageAccounts()::getByResourceGroup);

    // Verify containers have been deleted (Can't do this in submitControlledResourceDeletionFlight
    // because the get function takes a different number of arguments. Also no need to sleep another
    // 5 seconds.)
    com.azure.core.exception.HttpResponseException exception =
        assertThrows(
            com.azure.core.exception.HttpResponseException.class,
            () ->
                azureTestUtils
                    .getStorageManager()
                    .blobContainers()
                    .get(
                        azureTestUtils.getAzureCloudContext().getAzureResourceGroupId(),
                        accountResource.getStorageAccountName(),
                        containerName));
    assertEquals(404, exception.getResponse().getStatusCode());
  }

  @Test
  public void createAzureDiskControlledResource() throws InterruptedException {
    UUID workspaceUuid = azureTestUtils.createWorkspace(workspaceService);
    AuthenticatedUserRequest userRequest = userAccessUtils.defaultUserAuthRequest();
    createCloudContext(workspaceUuid, userRequest);

    final ApiAzureDiskCreationParameters creationParameters =
        ControlledResourceFixtures.getAzureDiskCreationParameters();

    // TODO: make this application-private resource once the POC supports it
    final UUID resourceId = UUID.randomUUID();
    ControlledAzureDiskResource resource =
        ControlledAzureDiskResource.builder()
            .common(
                ControlledResourceFields.builder()
                    .workspaceUuid(workspaceUuid)
                    .resourceId(resourceId)
                    .name(getAzureName("disk"))
                    .description(getAzureName("disk-desc"))
                    .cloningInstructions(CloningInstructions.COPY_RESOURCE)
                    .accessScope(AccessScopeType.fromApi(ApiAccessScope.SHARED_ACCESS))
                    .managedBy(ManagedByType.fromApi(ApiManagedBy.USER))
                    .build())
            .diskName(creationParameters.getName())
            .region(creationParameters.getRegion())
            .size(creationParameters.getSize())
            .build();

    // Submit a Disk creation flight and verify the resource exists in the workspace.
    createResource(workspaceUuid, userRequest, resource, WsmResourceType.CONTROLLED_AZURE_DISK);

    // clean up resources - delete disk resource
    submitControlledResourceDeletionFlight(
        workspaceUuid,
        userRequest,
        resource,
        azureTestUtils.getAzureCloudContext().getAzureResourceGroupId(),
        resource.getDiskName(),
        azureTestUtils.getComputeManager().disks()::getByResourceGroup);
  }

  @Test
  public void createAndDeleteAzureVmControlledResource() throws InterruptedException {
    // Setup workspace and cloud context
    UUID workspaceUuid = azureTestUtils.createWorkspace(workspaceService);
    AuthenticatedUserRequest userRequest = userAccessUtils.defaultUserAuthRequest();
    createCloudContext(workspaceUuid, userRequest);

    // Create ip
    ControlledAzureIpResource ipResource = createIp(workspaceUuid, userRequest);

    // Create disk
    ControlledAzureDiskResource diskResource = createDisk(workspaceUuid, userRequest);

    // Create network
    ControlledAzureNetworkResource networkResource = createNetwork(workspaceUuid, userRequest);

    final ApiAzureVmCreationParameters creationParameters =
        ControlledResourceFixtures.getAzureVmCreationParameters();

    // TODO: make this application-private resource once the POC supports it
    final UUID resourceId = UUID.randomUUID();
    ControlledAzureVmResource resource =
        ControlledAzureVmResource.builder()
            .common(
                ControlledResourceFields.builder()
                    .workspaceUuid(workspaceUuid)
                    .resourceId(resourceId)
                    .name(getAzureName("vm"))
                    .description(getAzureName("vm-desc"))
                    .cloningInstructions(CloningInstructions.COPY_RESOURCE)
                    .accessScope(AccessScopeType.fromApi(ApiAccessScope.SHARED_ACCESS))
                    .managedBy(ManagedByType.fromApi(ApiManagedBy.USER))
                    .build())
            .vmName(creationParameters.getName())
            .vmSize(creationParameters.getVmSize())
            .vmImage(AzureVmUtils.getImageData(creationParameters.getVmImage()))
            .region(creationParameters.getRegion())
            .ipId(ipResource.getResourceId())
            .diskId(diskResource.getResourceId())
            .networkId(networkResource.getResourceId())
            .build();

    // Submit a VM creation flight and verify the resource exists in the workspace.
    createVMResource(workspaceUuid, userRequest, resource, creationParameters);

    // Exercise resource enumeration for the underlying resources.
    // Verify that the resources we created are in the enumeration.
    List<WsmResource> resourceList =
        wsmResourceService.enumerateResources(workspaceUuid, null, null, 0, 100, userRequest);
    checkForResource(resourceList, ipResource);
    checkForResource(resourceList, diskResource);
    checkForResource(resourceList, networkResource);
    checkForResource(resourceList, resource);

    ComputeManager computeManager = azureTestUtils.getComputeManager();

    final VirtualMachine resolvedVm = getVirtualMachine(creationParameters, computeManager);

    // Submit a VM deletion flight.
    FlightState deleteFlightState =
        StairwayTestUtils.blockUntilFlightCompletes(
            jobService.getStairway(),
            DeleteControlledResourceFlight.class,
            azureTestUtils.deleteControlledResourceInputParameters(
                workspaceUuid, resourceId, userRequest, resource),
            STAIRWAY_FLIGHT_TIMEOUT,
            null);
    assertEquals(FlightStatus.SUCCESS, deleteFlightState.getFlightStatus());

    Thread.sleep(10000);
    resolvedVm
        .networkInterfaceIds()
        .forEach(
            nic ->
                assertThrows(
                    com.azure.core.exception.HttpResponseException.class,
                    () -> computeManager.networkManager().networks().getById(nic)));
    assertThrows(
        com.azure.core.exception.HttpResponseException.class,
        () -> computeManager.disks().getById(resolvedVm.osDiskId()));

    // clean up resources - vm deletion flight doesn't remove network and ip resources, so we need
    // to remove them;
    submitControlledResourceDeletionFlight(
        workspaceUuid,
        userRequest,
        networkResource,
        azureTestUtils.getAzureCloudContext().getAzureResourceGroupId(),
        networkResource.getNetworkName(),
        azureTestUtils.getComputeManager().networkManager().networks()::getByResourceGroup);

    submitControlledResourceDeletionFlight(
        workspaceUuid,
        userRequest,
        ipResource,
        azureTestUtils.getAzureCloudContext().getAzureResourceGroupId(),
        ipResource.getIpName(),
        azureTestUtils.getComputeManager().networkManager().publicIpAddresses()
            ::getByResourceGroup);

    // we need to delete data disk as well
    submitControlledResourceDeletionFlight(
        workspaceUuid,
        userRequest,
        diskResource,
        azureTestUtils.getAzureCloudContext().getAzureResourceGroupId(),
        diskResource.getDiskName(),
        azureTestUtils.getComputeManager().disks()::getByResourceGroup);
  }

  @Test
  public void createAndDeleteAzureVmControlledResourceWithCustomScriptExtension()
      throws InterruptedException {
    // Setup workspace and cloud context
    UUID workspaceUuid = azureTestUtils.createWorkspace(workspaceService);
    AuthenticatedUserRequest userRequest = userAccessUtils.defaultUserAuthRequest();
    createCloudContext(workspaceUuid, userRequest);

    // Create ip
    ControlledAzureIpResource ipResource = createIp(workspaceUuid, userRequest);

    // Create disk
    ControlledAzureDiskResource diskResource = createDisk(workspaceUuid, userRequest);

    // Create network
    ControlledAzureNetworkResource networkResource = createNetwork(workspaceUuid, userRequest);

    final ApiAzureVmCreationParameters creationParameters =
        ControlledResourceFixtures.getAzureVmCreationParametersWithCustomScriptExtension();

    // TODO: make this application-private resource once the POC supports it
    final UUID resourceId = UUID.randomUUID();
    ControlledAzureVmResource resource =
        ControlledAzureVmResource.builder()
            .common(
                ControlledResourceFields.builder()
                    .workspaceUuid(workspaceUuid)
                    .resourceId(resourceId)
                    .name(getAzureName("vm"))
                    .description(getAzureName("vm-desc"))
                    .cloningInstructions(CloningInstructions.COPY_RESOURCE)
                    .accessScope(AccessScopeType.fromApi(ApiAccessScope.SHARED_ACCESS))
                    .managedBy(ManagedByType.fromApi(ApiManagedBy.USER))
                    .build())
            .vmName(creationParameters.getName())
            .vmSize(creationParameters.getVmSize())
            .vmImage(AzureVmUtils.getImageData(creationParameters.getVmImage()))
            .region(creationParameters.getRegion())
            .ipId(ipResource.getResourceId())
            .diskId(diskResource.getResourceId())
            .networkId(networkResource.getResourceId())
            .build();

    // Submit a VM creation flight and verify the resource exists in the workspace.
    createVMResource(workspaceUuid, userRequest, resource, creationParameters);

    // Exercise resource enumeration for the underlying resources.
    // Verify that the resources we created are in the enumeration.
    List<WsmResource> resourceList =
        wsmResourceService.enumerateResources(workspaceUuid, null, null, 0, 100, userRequest);
    checkForResource(resourceList, ipResource);
    checkForResource(resourceList, diskResource);
    checkForResource(resourceList, networkResource);
    checkForResource(resourceList, resource);

    ComputeManager computeManager = azureTestUtils.getComputeManager();

    final VirtualMachine resolvedVm = getVirtualMachine(creationParameters, computeManager);

    // Submit a VM deletion flight.
    FlightState deleteFlightState =
        StairwayTestUtils.blockUntilFlightCompletes(
            jobService.getStairway(),
            DeleteControlledResourceFlight.class,
            azureTestUtils.deleteControlledResourceInputParameters(
                workspaceUuid, resourceId, userRequest, resource),
            STAIRWAY_FLIGHT_TIMEOUT,
            null);
    assertEquals(FlightStatus.SUCCESS, deleteFlightState.getFlightStatus());

    Thread.sleep(10000);
    resolvedVm
        .networkInterfaceIds()
        .forEach(
            nic ->
                assertThrows(
                    com.azure.core.exception.HttpResponseException.class,
                    () -> computeManager.networkManager().networks().getById(nic)));
    assertThrows(
        com.azure.core.exception.HttpResponseException.class,
        () -> computeManager.disks().getById(resolvedVm.osDiskId()));

    // clean up resources - vm deletion flight doesn't remove network and ip resources, so we need
    // to remove them;
    submitControlledResourceDeletionFlight(
        workspaceUuid,
        userRequest,
        networkResource,
        azureTestUtils.getAzureCloudContext().getAzureResourceGroupId(),
        networkResource.getNetworkName(),
        azureTestUtils.getComputeManager().networkManager().networks()::getByResourceGroup);

    submitControlledResourceDeletionFlight(
        workspaceUuid,
        userRequest,
        ipResource,
        azureTestUtils.getAzureCloudContext().getAzureResourceGroupId(),
        ipResource.getIpName(),
        azureTestUtils.getComputeManager().networkManager().publicIpAddresses()
            ::getByResourceGroup);

    // we need to delete data disk as well
    submitControlledResourceDeletionFlight(
        workspaceUuid,
        userRequest,
        diskResource,
        azureTestUtils.getAzureCloudContext().getAzureResourceGroupId(),
        diskResource.getDiskName(),
        azureTestUtils.getComputeManager().disks()::getByResourceGroup);
  }

  @Test
  public void createVmWithFailureMakeSureNetworkInterfaceIsNotAbandoned()
      throws InterruptedException {
    // Setup workspace and cloud context
    UUID workspaceUuid = azureTestUtils.createWorkspace(workspaceService);
    AuthenticatedUserRequest userRequest = userAccessUtils.defaultUserAuthRequest();

    // Cloud context needs to be created first
    FlightState createAzureContextFlightState =
        StairwayTestUtils.blockUntilFlightCompletes(
            jobService.getStairway(),
            CreateAzureContextFlight.class,
            azureTestUtils.createAzureContextInputParameters(workspaceUuid, userRequest),
            STAIRWAY_FLIGHT_TIMEOUT,
            null);

    assertEquals(FlightStatus.SUCCESS, createAzureContextFlightState.getFlightStatus());
    assertTrue(
        workspaceService.getAuthorizedAzureCloudContext(workspaceUuid, userRequest).isPresent());

    // Create disk
    ControlledAzureDiskResource diskResource = createDisk(workspaceUuid, userRequest);

    // Create network
    ControlledAzureNetworkResource networkResource = createNetwork(workspaceUuid, userRequest);

    final ApiAzureVmCreationParameters creationParameters =
        ControlledResourceFixtures.getInvalidAzureVmCreationParameters();

    final UUID resourceId = UUID.randomUUID();
    ControlledAzureVmResource vmResource =
        ControlledAzureVmResource.builder()
            .common(
                ControlledResourceFields.builder()
                    .workspaceUuid(workspaceUuid)
                    .resourceId(resourceId)
                    .name(getAzureName("vm"))
                    .description(getAzureName("vm-desc"))
                    .cloningInstructions(CloningInstructions.COPY_RESOURCE)
                    .accessScope(AccessScopeType.fromApi(ApiAccessScope.SHARED_ACCESS))
                    .managedBy(ManagedByType.fromApi(ApiManagedBy.USER))
                    .build())
            .vmName(creationParameters.getName())
            .vmSize(creationParameters.getVmSize())
            .vmImage(AzureVmUtils.getImageData(creationParameters.getVmImage()))
            .region(creationParameters.getRegion())
            .diskId(diskResource.getResourceId())
            .networkId(networkResource.getResourceId())
            .build();

    // Submit a VM creation flight. This flight will fail. It is made intentionally.
    // We need this to test undo step and check if network interface is deleted.
    FlightState vmCreationFlightState =
        StairwayTestUtils.blockUntilFlightCompletes(
            jobService.getStairway(),
            CreateControlledResourceFlight.class,
            azureTestUtils.createControlledResourceInputParameters(
                workspaceUuid, userRequest, vmResource, creationParameters),
            STAIRWAY_FLIGHT_TIMEOUT,
            null);

    // VM flight was failed intentionally
    assertEquals(FlightStatus.ERROR, vmCreationFlightState.getFlightStatus());
    assertThrows(
        bio.terra.workspace.service.resource.exception.ResourceNotFoundException.class,
        () ->
            controlledResourceService.getControlledResource(
                workspaceUuid, resourceId, userRequest));

    ComputeManager computeManager = azureTestUtils.getComputeManager();
    AzureCloudContext azureCloudContext =
        vmCreationFlightState
            .getResultMap()
            .get()
            .get("azureCloudContext", AzureCloudContext.class);
    // validate that VM doesn't exist
    try {
      computeManager
          .virtualMachines()
          .getByResourceGroup(azureCloudContext.getAzureResourceGroupId(), vmResource.getVmName());
      fail("VM should not exist");
    } catch (ManagementException e) {
      assertEquals(404, e.getResponse().getStatusCode());
    }

    assertTrue(vmCreationFlightState.getResultMap().isPresent());
    assertTrue(
        vmCreationFlightState
            .getResultMap()
            .get()
            .containsKey(AzureVmHelper.WORKING_MAP_NETWORK_INTERFACE_KEY));
    assertTrue(vmCreationFlightState.getResultMap().get().containsKey("azureCloudContext"));

    // validate that our network interface doesn't exist
    String networkInterfaceName =
        vmCreationFlightState
            .getResultMap()
            .get()
            .get(AzureVmHelper.WORKING_MAP_NETWORK_INTERFACE_KEY, String.class);
    try {
      computeManager
          .networkManager()
          .networkInterfaces()
          .getByResourceGroup(azureCloudContext.getAzureResourceGroupId(), networkInterfaceName);
      fail("Network interface should not exist");
    } catch (ManagementException e) {
      assertEquals(404, e.getResponse().getStatusCode());
    }

    // since VM is not created we need to submit a disk deletion and network deletion flights
    // separately
    Thread.sleep(10000);
    submitControlledResourceDeletionFlight(
        workspaceUuid,
        userRequest,
        networkResource,
        azureTestUtils.getAzureCloudContext().getAzureResourceGroupId(),
        networkResource.getNetworkName(),
        azureTestUtils.getComputeManager().networkManager().networks()::getByResourceGroup);

    // despite on the fact that vm hasn't been created this flight might be completed with error.
    // It might complain that disk is attached to a vm. As a result we need retry here.
    // Number of cloud retry rules attempts might be not enough.
    int deleteAttemptsNumber = 5;
    FlightState deleteDiskResourceFlightState;
    do {
      deleteDiskResourceFlightState =
          StairwayTestUtils.blockUntilFlightCompletes(
              jobService.getStairway(),
              DeleteControlledResourceFlight.class,
              azureTestUtils.deleteControlledResourceInputParameters(
                  workspaceUuid, diskResource.getResourceId(), userRequest, diskResource),
              STAIRWAY_FLIGHT_TIMEOUT,
              null);
      deleteAttemptsNumber--;
    } while (!deleteDiskResourceFlightState.getFlightStatus().equals(FlightStatus.SUCCESS)
        || deleteAttemptsNumber == 0);
    assertEquals(FlightStatus.SUCCESS, deleteDiskResourceFlightState.getFlightStatus());

    assertThrows(
        com.azure.core.exception.HttpResponseException.class,
        () ->
            computeManager
                .disks()
                .getByResourceGroup(
                    azureCloudContext.getAzureResourceGroupId(), diskResource.getDiskName()));
  }

  @Test
  public void createAndDeleteAzureVmControlledResourceWithCustomScriptExtensionWithNoPublicIp()
      throws InterruptedException {
    // Setup workspace and cloud context
    UUID workspaceUuid = azureTestUtils.createWorkspace(workspaceService);
    AuthenticatedUserRequest userRequest = userAccessUtils.defaultUserAuthRequest();
    createCloudContext(workspaceUuid, userRequest);

    // Create disk
    ControlledAzureDiskResource diskResource = createDisk(workspaceUuid, userRequest);

    // Create network
    ControlledAzureNetworkResource networkResource = createNetwork(workspaceUuid, userRequest);

    final ApiAzureVmCreationParameters creationParameters =
        ControlledResourceFixtures.getAzureVmCreationParametersWithCustomScriptExtension();

    // TODO: make this application-private resource once the POC supports it
    final UUID resourceId = UUID.randomUUID();
    ControlledAzureVmResource resource =
        ControlledAzureVmResource.builder()
            .common(
                ControlledResourceFields.builder()
                    .workspaceUuid(workspaceUuid)
                    .resourceId(resourceId)
                    .name(getAzureName("vm"))
                    .description(getAzureName("vm-desc"))
                    .cloningInstructions(CloningInstructions.COPY_RESOURCE)
                    .accessScope(AccessScopeType.fromApi(ApiAccessScope.SHARED_ACCESS))
                    .managedBy(ManagedByType.fromApi(ApiManagedBy.USER))
                    .build())
            .vmName(creationParameters.getName())
            .vmSize(creationParameters.getVmSize())
            .vmImage(AzureVmUtils.getImageData(creationParameters.getVmImage()))
            .region(creationParameters.getRegion())
            // .ipId(ipResource.getResourceId())
            .diskId(diskResource.getResourceId())
            .networkId(networkResource.getResourceId())
            .build();

    // Submit a VM creation flight and verify the resource exists in the workspace.
    createVMResource(workspaceUuid, userRequest, resource, creationParameters);

    // Exercise resource enumeration for the underlying resources.
    // Verify that the resources we created are in the enumeration.
    List<WsmResource> resourceList =
        wsmResourceService.enumerateResources(workspaceUuid, null, null, 0, 100, userRequest);
    // checkForResource(resourceList, ipResource);
    checkForResource(resourceList, diskResource);
    checkForResource(resourceList, networkResource);
    checkForResource(resourceList, resource);

    ComputeManager computeManager = azureTestUtils.getComputeManager();

    final VirtualMachine resolvedVm = getVirtualMachine(creationParameters, computeManager);

    // Submit a VM deletion flight.
    FlightState deleteFlightState =
        StairwayTestUtils.blockUntilFlightCompletes(
            jobService.getStairway(),
            DeleteControlledResourceFlight.class,
            azureTestUtils.deleteControlledResourceInputParameters(
                workspaceUuid, resourceId, userRequest, resource),
            STAIRWAY_FLIGHT_TIMEOUT,
            null);
    assertEquals(FlightStatus.SUCCESS, deleteFlightState.getFlightStatus());

    Thread.sleep(10000);
    resolvedVm
        .networkInterfaceIds()
        .forEach(
            nic ->
                assertThrows(
                    com.azure.core.exception.HttpResponseException.class,
                    () -> computeManager.networkManager().networks().getById(nic)));
    assertThrows(
        com.azure.core.exception.HttpResponseException.class,
        () -> computeManager.disks().getById(resolvedVm.osDiskId()));

    // clean up resources - we need to remove only network since current VM has no public ip;
    submitControlledResourceDeletionFlight(
        workspaceUuid,
        userRequest,
        networkResource,
        azureTestUtils.getAzureCloudContext().getAzureResourceGroupId(),
        networkResource.getNetworkName(),
        azureTestUtils.getComputeManager().networkManager().networks()::getByResourceGroup);

    // we need to delete data disk as well
    submitControlledResourceDeletionFlight(
        workspaceUuid,
        userRequest,
        diskResource,
        azureTestUtils.getAzureCloudContext().getAzureResourceGroupId(),
        diskResource.getDiskName(),
        azureTestUtils.getComputeManager().disks()::getByResourceGroup);
  }

  private VirtualMachine getVirtualMachine(
      ApiAzureVmCreationParameters creationParameters, ComputeManager computeManager)
      throws InterruptedException {
    var retries = 20;
    VirtualMachine vmTemp = null;
    while (vmTemp == null) {
      try {
        retries = retries - 1;

        if (retries >= 0) {
          vmTemp =
              computeManager
                  .virtualMachines()
                  .getByResourceGroup(
                      azureTestUtils.getAzureCloudContext().getAzureResourceGroupId(),
                      creationParameters.getName());
        } else
          throw new RuntimeException(
              String.format("%s is not created in time in Azure", creationParameters.getName()));
      } catch (com.azure.core.exception.HttpResponseException ex) {
        if (ex.getResponse().getStatusCode() == 404) Thread.sleep(10000);
        else throw ex;
      }
    }
    return vmTemp;
  }

  private void checkForResource(List<WsmResource> resourceList, ControlledResource resource) {
    for (WsmResource wsmResource : resourceList) {
      if (wsmResource.getResourceId().equals(resource.getResourceId())) {
        assertEquals(resource.getResourceType(), wsmResource.getResourceType());
        assertEquals(resource.getWorkspaceId(), wsmResource.getWorkspaceId());
        assertEquals(resource.getName(), wsmResource.getName());
        return;
      }
    }
    fail("Failed to find resource in resource list");
  }

  private ControlledAzureDiskResource createDisk(
      UUID workspaceUuid, AuthenticatedUserRequest userRequest) throws InterruptedException {
    final ApiAzureDiskCreationParameters creationParameters =
        ControlledResourceFixtures.getAzureDiskCreationParameters();

    // TODO: make this application-private resource once the POC supports it
    final UUID resourceId = UUID.randomUUID();
    ControlledAzureDiskResource resource =
        ControlledAzureDiskResource.builder()
            .common(
                ControlledResourceFields.builder()
                    .workspaceUuid(workspaceUuid)
                    .resourceId(resourceId)
                    .name(getAzureName("disk"))
                    .description(getAzureName("disk-desc"))
                    .cloningInstructions(CloningInstructions.COPY_RESOURCE)
                    .accessScope(AccessScopeType.fromApi(ApiAccessScope.SHARED_ACCESS))
                    .managedBy(ManagedByType.fromApi(ApiManagedBy.USER))
                    .build())
            .diskName(creationParameters.getName())
            .region(creationParameters.getRegion())
            .size(creationParameters.getSize())
            .build();

    // Submit a Disk creation flight.
    FlightState flightState =
        StairwayTestUtils.blockUntilFlightCompletes(
            jobService.getStairway(),
            CreateControlledResourceFlight.class,
            azureTestUtils.createControlledResourceInputParameters(
                workspaceUuid, userRequest, resource),
            STAIRWAY_FLIGHT_TIMEOUT,
            null);

    assertEquals(FlightStatus.SUCCESS, flightState.getFlightStatus());
    return resource;
  }

  ControlledAzureIpResource createIp(UUID workspaceUuid, AuthenticatedUserRequest userRequest)
      throws InterruptedException {
    final ApiAzureIpCreationParameters ipCreationParameters =
        ControlledResourceFixtures.getAzureIpCreationParameters();

    // TODO: make this application-private resource once the POC supports it
    final UUID resourceId = UUID.randomUUID();
    ControlledAzureIpResource resource =
        ControlledAzureIpResource.builder()
            .common(
                ControlledResourceFields.builder()
                    .workspaceUuid(workspaceUuid)
                    .resourceId(resourceId)
                    .name(getAzureName("ip"))
                    .description(getAzureName("ip-desc"))
                    .cloningInstructions(CloningInstructions.COPY_RESOURCE)
                    .accessScope(AccessScopeType.fromApi(ApiAccessScope.SHARED_ACCESS))
                    .managedBy(ManagedByType.fromApi(ApiManagedBy.USER))
                    .build())
            .ipName(ipCreationParameters.getName())
            .region(ipCreationParameters.getRegion())
            .build();

    // Submit an IP creation flight.
    FlightState flightState =
        StairwayTestUtils.blockUntilFlightCompletes(
            jobService.getStairway(),
            CreateControlledResourceFlight.class,
            azureTestUtils.createControlledResourceInputParameters(
                workspaceUuid, userRequest, resource),
            STAIRWAY_FLIGHT_TIMEOUT,
            null);

    assertEquals(FlightStatus.SUCCESS, flightState.getFlightStatus());

    return resource;
  }

  private ControlledAzureNetworkResource createNetwork(
      UUID workspaceUuid, AuthenticatedUserRequest userRequest) throws InterruptedException {
    final ApiAzureNetworkCreationParameters creationParameters =
        ControlledResourceFixtures.getAzureNetworkCreationParameters();

    // TODO: make this application-private resource once the POC supports it
    final UUID resourceId = UUID.randomUUID();
    ControlledAzureNetworkResource resource =
        ControlledAzureNetworkResource.builder()
            .common(
                ControlledResourceFields.builder()
                    .workspaceUuid(workspaceUuid)
                    .resourceId(resourceId)
                    .name(getAzureName("network"))
                    .description(getAzureName("network-desc"))
                    .cloningInstructions(CloningInstructions.COPY_RESOURCE)
                    .accessScope(AccessScopeType.fromApi(ApiAccessScope.SHARED_ACCESS))
                    .managedBy(ManagedByType.fromApi(ApiManagedBy.USER))
                    .build())
            .networkName(creationParameters.getName())
            .region(creationParameters.getRegion())
            .subnetName(creationParameters.getSubnetName())
            .addressSpaceCidr(creationParameters.getAddressSpaceCidr())
            .subnetAddressCidr(creationParameters.getSubnetAddressCidr())
            .build();

    // Submit a Disk creation flight.
    FlightState flightState =
        StairwayTestUtils.blockUntilFlightCompletes(
            jobService.getStairway(),
            CreateControlledResourceFlight.class,
            azureTestUtils.createControlledResourceInputParameters(
                workspaceUuid, userRequest, resource),
            STAIRWAY_FLIGHT_TIMEOUT,
            null);

    assertEquals(FlightStatus.SUCCESS, flightState.getFlightStatus());
    return resource;
  }

  private static String getAzureName(String tag) {
    final String id = UUID.randomUUID().toString().substring(0, 6);
    return String.format("wsm-integ-%s-%s", tag, id);
  }

  @Test
  public void createAzureNetworkControlledResource() throws InterruptedException {
    UUID workspaceUuid = azureTestUtils.createWorkspace(workspaceService);
    AuthenticatedUserRequest userRequest = userAccessUtils.defaultUserAuthRequest();
    createCloudContext(workspaceUuid, userRequest);

    final ApiAzureNetworkCreationParameters creationParams =
        ControlledResourceFixtures.getAzureNetworkCreationParameters();

    // TODO: make this application-private resource once the POC supports it
    final UUID resourceId = UUID.randomUUID();
    ControlledAzureNetworkResource resource =
        ControlledAzureNetworkResource.builder()
            .common(
                ControlledResourceFields.builder()
                    .workspaceUuid(workspaceUuid)
                    .resourceId(resourceId)
                    .name("testNetwork")
                    .description("testDesc")
                    .cloningInstructions(CloningInstructions.COPY_RESOURCE)
                    .accessScope(AccessScopeType.fromApi(ApiAccessScope.SHARED_ACCESS))
                    .managedBy(ManagedByType.fromApi(ApiManagedBy.USER))
                    .build())
            .networkName(creationParams.getName())
            .region(creationParams.getRegion())
            .subnetName(creationParams.getSubnetName())
            .addressSpaceCidr(creationParams.getAddressSpaceCidr())
            .subnetAddressCidr(creationParams.getSubnetAddressCidr())
            .build();

    // Submit a Network creation flight and verify the resource exists in the workspace.
    createResource(workspaceUuid, userRequest, resource, WsmResourceType.CONTROLLED_AZURE_NETWORK);

    // clean up resources - delete network resource;
    submitControlledResourceDeletionFlight(
        workspaceUuid,
        userRequest,
        resource,
        azureTestUtils.getAzureCloudContext().getAzureResourceGroupId(),
        resource.getNetworkName(),
        azureTestUtils.getComputeManager().networkManager().networks()::getByResourceGroup);
  }

  private <T extends ControlledResource, R> void submitControlledResourceDeletionFlight(
      UUID workspaceUuid,
      AuthenticatedUserRequest userRequest,
      T controlledResource,
      String azureResourceGroupId,
      String resourceName,
      BiFunction<String, String, R> findResource)
      throws InterruptedException {
    FlightState deleteControlledResourceFlightState =
        StairwayTestUtils.blockUntilFlightCompletes(
            jobService.getStairway(),
            DeleteControlledResourceFlight.class,
            azureTestUtils.deleteControlledResourceInputParameters(
                workspaceUuid, controlledResource.getResourceId(), userRequest, controlledResource),
            STAIRWAY_FLIGHT_TIMEOUT,
            null);

    assertEquals(FlightStatus.SUCCESS, deleteControlledResourceFlightState.getFlightStatus());

    if (findResource != null) {
      TimeUnit.SECONDS.sleep(5);
      com.azure.core.exception.HttpResponseException exception =
          assertThrows(
              com.azure.core.exception.HttpResponseException.class,
              () -> findResource.apply(azureResourceGroupId, resourceName));
      assertEquals(404, exception.getResponse().getStatusCode());
    }
  }
}
